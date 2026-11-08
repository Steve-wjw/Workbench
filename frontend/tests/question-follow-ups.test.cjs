const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const vm = require("node:vm");
const { buildSync } = require("esbuild");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const root = path.resolve(__dirname, "..");
function loadModule(entry, extraExports = "") {
  const source = readFileSync(path.join(root, "src", entry), "utf8");
  const result = buildSync({
    stdin: { contents: `${source}\n${extraExports}`, resolveDir: path.join(root, "src"), loader: "tsx" },
    bundle: true, write: false, platform: "node", format: "cjs",
    external: ["react", "react-dom/server"], define: { "import.meta.env": "{}" },
  });
  const module = { exports: {} };
  vm.runInNewContext(result.outputFiles[0].text, { module, exports: module.exports, require, React });
  return module.exports;
}
const { extractFollowUpTitles, followUpText, parseFollowUps, serializeFollowUps } = loadModule("questionFollowUps.ts");
const { QuestionsPage, QuestionRow, QuestionDetail, QuestionModal, MarkdownPreview, FollowUpCollection, FollowUpForm } = loadModule(
  "App.tsx", "export { QuestionsPage, QuestionRow, QuestionDetail, QuestionModal, MarkdownPreview, FollowUpCollection, FollowUpForm };",
);
const titles = (markdown) => Array.from(extractFollowUpTitles(markdown));
const markdown = "## 为什么选择 RAG？\n追问答案不应出现在列表\n### 答案细节\n解释\n## 如何评估效果？\n补充内容";
const question = {
  id: "1", question: "什么是 RAG？", answer: "主问题答案不应出现在列表",
  followUpQuestions: markdown, difficulty: "MEDIUM", masteryStatus: "LEARNING",
  tags: [], updatedAt: "2026-11-04T08:00:00Z",
};
const noop = () => {};
const render = (component, props) => renderToStaticMarkup(React.createElement(component, props));

test("extracts only outermost follow-up headings in order", () => {
  assert.deepEqual(titles(markdown), ["为什么选择 RAG？", "如何评估效果？"]);
});
test("ignores backtick and tilde code blocks, including shorter nested fences", () => {
  assert.deepEqual(titles("````md\n## code\n```\n## still code\n````\n~~~\n# shell comment\n~~~\n## actual"), ["actual"]);
});
test("handles CRLF, indentation, closing hashes and all ATX heading levels", () => {
  assert.deepEqual(titles("  #### first ####  \r\n###### child\r\n#### second\r\n    # indented code"), ["first", "second"]);
});
test("empty values do not create follow-ups", () => {
  for (const input of [undefined, null, "", " \n"]) {
    assert.deepEqual(titles(input), []);
  }
});
test("legacy text without headings is retained as a supplemental answer", () => {
  for (const input of ["normal text", "#not a heading", "####### invalid", "## ###"]) {
    assert.equal(parseFollowUps(input)[0].question, "补充追问");
    assert.equal(parseFollowUps(input)[0].answer, input);
    assert.deepEqual(titles(input), []);
  }
});
test("legacy supplemental content does not produce a placeholder row or branch in lists", () => {
  const html = render(QuestionRow, { question: { ...question, followUpQuestions: "旧版补充正文" }, onSelect: noop });
  assert.doesNotMatch(html, /补充追问|class="follow-up-titles"|class="follow-up-title"/);
  assert.equal(parseFollowUps("旧版补充正文")[0].answer, "旧版补充正文");
});
test("real follow-up titles are retained, including explicitly named supplemental questions", () => {
  assert.deepEqual(titles("前言\n## 真实追问\n回答"), ["真实追问"]);
  assert.deepEqual(titles(serializeFollowUps([{ question: "补充追问", answer: "主动添加的回答" }])), ["补充追问"]);
});
test("legacy conversion preserves preamble, answer subheadings and code blocks", () => {
  const parsed = parseFollowUps("前言\n\n## First\n### Answer\n```\n## code\n```\n## Second\nlast");
  assert.equal(parsed.length, 3);
  assert.equal(parsed[0].answer, "前言\n");
  assert.equal(parsed[1].question, "First");
  assert.equal(parsed[1].answer, "### Answer\n```\n## code\n```");
  assert.equal(parsed[2].answer, "last");
});
test("structured questions and answers round-trip exactly with independent Markdown headings", () => {
  const items = [
    { question: "## First\n\nMore context", answer: "# Answer heading\n\n- first\n  - nested\n\n```md\n## not a question\n```" },
    { question: "**Second**", answer: '  preserved whitespace\n{"quoted":"content"}\n' },
  ];
  const parsed = parseFollowUps(serializeFollowUps(items));
  assert.equal(JSON.stringify(parsed), JSON.stringify(items));
  assert.deepEqual(titles(serializeFollowUps(items)), ["First", "**Second**"]);
});
test("removing the last follow-up writes an empty string", () => {
  assert.equal(serializeFollowUps([]), "");
  assert.equal(parseFollowUps(serializeFollowUps([])).length, 0);
});
test("search and tag recommendations use actual text, not JSON field names", () => {
  assert.equal(followUpText(serializeFollowUps([{ question: "追问标题", answer: "回答内容" }])), "追问标题\n回答内容");
});
test("malformed structured payloads are retained instead of silently dropping content", () => {
  for (const input of ['{"version":1,"items":[null]}', '{"version":1,"items":[{"question":"Missing answer"}]}', '{"version":2,"items":[]}']) {
    assert.equal(parseFollowUps(input)[0].answer, input);
  }
});
test("unclosed code blocks cannot leak headings into the list", () => {
  assert.deepEqual(titles("## actual\n```md\n## not a question"), ["actual"]);
});
test("question list displays child titles but hides main and follow-up answers", () => {
  const html = render(QuestionsPage, { questions: [question], modules: [], tags: [], selected: null, onSelect: noop, onNew: noop, onEdit: noop, onDelete: noop, onSaveFollowUps: noop });
  assert.match(html, /class="follow-up-titles" role="list"/);
  assert.match(html, /为什么选择 RAG？/);
  assert.match(html, /如何评估效果？/);
  assert.doesNotMatch(html, /主问题答案不应出现在列表|追问答案不应出现在列表|答案细节/);
});
test("homepage question rows also show child titles without answers", () => {
  const html = render(QuestionRow, { question, onSelect: noop });
  assert.match(html, /如何评估效果？/);
  assert.doesNotMatch(html, /追问答案不应出现在列表/);
});
test("detail shows a plus button and rendered follow-ups, never a persistent textarea", () => {
  const props = { question, onBack: noop, onEdit: noop, onDelete: noop, onSaveFollowUps: noop };
  const html = render(QuestionDetail, props);
  assert.match(html, /aria-label="添加追问"/);
  assert.doesNotMatch(html, /<textarea/);
  assert.match(html, /class="follow-up-question"/);
  assert.match(html, /class="follow-up-answer"/);
  assert.match(html, /追问答案不应出现在列表/);
  const empty = render(QuestionDetail, { ...props, question: { ...question, followUpQuestions: undefined } });
  assert.match(empty, /aria-label="添加追问"/);
  assert.doesNotMatch(empty, /<textarea|class="follow-up-item"/);
});
test("create and edit forms use the plus control without a merged Markdown field", () => {
  const props = { modules: [], tags: [], onCreateModule: noop, onCreateTag: noop, onClose: noop, onSaved: noop };
  for (const value of ["new", question]) {
    const html = render(QuestionModal, { ...props, value });
    assert.match(html, /aria-label="添加追问"/);
    assert.equal((html.match(/<textarea/g) ?? []).length, 3);
    assert.equal((html.match(/<form/g) ?? []).length, 1);
  }
});
test("the add/edit composer has exactly two labeled fields and cannot submit its parent form", () => {
  const html = render(FollowUpForm, { value: { question: "", answer: "" }, saving: false, creating: true, onSave: noop, onCancel: noop });
  assert.match(html, /<span>追问<\/span>/);
  assert.match(html, /<span>回答<\/span>/);
  assert.equal((html.match(/<textarea/g) ?? []).length, 2);
  assert.doesNotMatch(html, /<form/);
  assert.equal((html.match(/type="button"/g) ?? []).length, 2);
});
test("collection renders structured question and answer Markdown separately and supports raw display", () => {
  const item = { question: "**Question**", answer: "1. First\n2. Second\n\n> Quote" };
  const props = { value: serializeFollowUps([item]), onChange: noop };
  const html = render(FollowUpCollection, props);
  assert.match(html, /<strong>Question<\/strong>/);
  assert.match(html, /<ol/);
  assert.match(html, /<blockquote>/);
  const raw = render(FollowUpCollection, { ...props, view: "raw" });
  assert.match(raw, /\*\*Question\*\*/);
  assert.doesNotMatch(raw, /<strong>Question<\/strong>|<ol/);
});
test("heading content is escaped, not injected as HTML", () => {
  const html = render(QuestionRow, { question: { ...question, followUpQuestions: '## <img src=x onerror="alert(1)">' }, onSelect: noop });
  assert.match(html, /&lt;img/);
  assert.doesNotMatch(html, /<img/);
});
test("formatted titles stay readable without creating links inside a row button", () => {
  const html = render(QuestionRow, { question: { ...question, followUpQuestions: "## **RAG** 与 `SQL`：[参考](https://example.com)" }, onSelect: noop });
  assert.match(html, /<strong>RAG<\/strong>/);
  assert.match(html, /参考/);
  assert.doesNotMatch(html, /<a\s|href=/);
});
test("follow-up previews handle tilde fences and deeper headings", () => {
  const html = render(MarkdownPreview, { markdown: "~~~md\n# code\n~~~\n#### deep heading" });
  assert.match(html, /<pre><code class="language-md"># code<\/code><\/pre>/);
  assert.match(html, /<h6>/);
});
test("Markdown handles tables, nested lists, tasks, emphasis, quotes and inline code", () => {
  const html = render(MarkdownPreview, { markdown: "| Name | Value |\n| --- | --- |\n| A | `B` |\n\n- [x] done\n- parent\n  - *child*\n\n> **quote**" });
  for (const pattern of [/<table>/, /<th/, /<td/, /type="checkbox"/, /<em>child<\/em>/, /<blockquote>/, /<strong>quote<\/strong>/, /class="inline-code"/]) assert.match(html, pattern);
});
test("Markdown escapes raw HTML and rejects script links and image sources", () => {
  const html = render(MarkdownPreview, { markdown: '<script>alert(1)</script>\n\n[unsafe](javascript:alert)\n\n![image](data:text/html,unsafe)\n\n[valid](https://example.com)' });
  assert.doesNotMatch(html, /<script|href="javascript:|src="data:/);
  assert.match(html, /&lt;script&gt;/);
  assert.match(html, /href="https:\/\/example.com"/);
});
