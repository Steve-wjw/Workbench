const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const vm = require("node:vm");
const { buildSync } = require("esbuild");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const root = path.resolve(__dirname, "..");
function load(entry, globals = {}, extraExports = "") {
  const bundle = buildSync({
    stdin: { contents: `${readFileSync(path.join(root, "src", entry), "utf8")}\n${extraExports}`, resolveDir: path.join(root, "src"), loader: "tsx" },
    bundle: true, write: false, platform: "node", format: "cjs",
    external: ["react"], define: { "import.meta.env": "{}" },
  });
  const module = { exports: {} };
  vm.runInNewContext(bundle.outputFiles[0].text, { module, exports: module.exports, require, React, ...globals });
  return module.exports;
}
function storage() {
  const values = new Map();
  return { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
}
const plain = (value) => JSON.parse(JSON.stringify(value));
const home = { view: "home", selectedQuestionId: null, selectedDocumentId: null, selectedNewsId: null };

test("first visit and server rendering safely default to home", () => {
  assert.deepEqual(plain(load("navigationState.ts").readNavigationState()), home);
  assert.deepEqual(plain(load("navigationState.ts", { window: { sessionStorage: storage() } }).readNavigationState()), home);
});
test("reload remembers every module within the same browser tab", () => {
  const sessionStorage = storage();
  for (const view of ["home", "news", "questions", "documents", "tags", "modules"]) {
    load("navigationState.ts", { window: { sessionStorage } }).saveNavigationState({ ...home, view });
    assert.equal(load("navigationState.ts", { window: { sessionStorage } }).readNavigationState().view, view);
  }
});
test("question, document and news details retain only the active resource ID", () => {
  const sessionStorage = storage();
  const navigation = load("navigationState.ts", { window: { sessionStorage } });
  for (const [view, selectedKey] of [["questions", "selectedQuestionId"], ["documents", "selectedDocumentId"], ["news", "selectedNewsId"]]) {
    navigation.saveNavigationState({ view, selectedQuestionId: "42", selectedDocumentId: "document-id", selectedNewsId: "news-id" });
    const saved = plain(navigation.readNavigationState());
    assert.equal(saved.view, view);
    assert.ok(saved[selectedKey]);
    for (const key of ["selectedQuestionId", "selectedDocumentId", "selectedNewsId"]) {
      if (key !== selectedKey) assert.equal(saved[key], null);
    }
  }
});
test("returning to the list clears the saved detail selection", () => {
  const sessionStorage = storage();
  const navigation = load("navigationState.ts", { window: { sessionStorage } });
  navigation.saveNavigationState({ ...home, view: "questions", selectedQuestionId: "42" });
  navigation.saveNavigationState({ ...home, view: "questions" });
  assert.deepEqual(plain(navigation.readNavigationState()), { ...home, view: "questions" });
});
test("corrupt, unknown and obsolete navigation values cannot break startup", () => {
  const sessionStorage = storage();
  const navigation = load("navigationState.ts", { window: { sessionStorage } });
  for (const raw of ["invalid JSON", "null", "[]", "123", '{"view":"removed-page","selectedQuestionId":"old-id"}']) {
    sessionStorage.setItem(navigation.NAVIGATION_STORAGE_KEY, raw);
    assert.deepEqual(plain(navigation.readNavigationState()), home);
  }
  for (const id of ["", "  ", 123, {}, "a".repeat(201), "0", "-1", "1.5", "01", "1e2", "9223372036854775808", "bb34fb41-246e-42cb-adcd-d9a5bc716bb8"]) {
    sessionStorage.setItem(navigation.NAVIGATION_STORAGE_KEY, JSON.stringify({ view: "questions", selectedQuestionId: id }));
    assert.equal(navigation.readNavigationState().selectedQuestionId, null);
  }
});
test("numeric question IDs round-trip without losing BIGINT precision", () => {
  const sessionStorage = storage();
  const navigation = load("navigationState.ts", { window: { sessionStorage } });
  for (const selectedQuestionId of ["1", "42", "9007199254740993", "9223372036854775807"]) {
    navigation.saveNavigationState({ ...home, view: "questions", selectedQuestionId });
    assert.equal(navigation.readNavigationState().selectedQuestionId, selectedQuestionId);
  }
});
test("migration clears obsolete UUID detail IDs but keeps the questions module", () => {
  const sessionStorage = storage();
  const navigation = load("navigationState.ts", { window: { sessionStorage } });
  sessionStorage.setItem(navigation.NAVIGATION_STORAGE_KEY, JSON.stringify({
    ...home, view: "questions", selectedQuestionId: "bb34fb41-246e-42cb-adcd-d9a5bc716bb8",
  }));
  assert.deepEqual(plain(navigation.readNavigationState()), { ...home, view: "questions" });
});
test("browser storage failures do not prevent navigation", () => {
  const browser = {};
  Object.defineProperty(browser, "sessionStorage", { get() { throw new Error("Storage blocked"); } });
  const navigation = load("navigationState.ts", { window: browser });
  assert.deepEqual(plain(navigation.readNavigationState()), home);
  assert.doesNotThrow(() => navigation.saveNavigationState({ ...home, view: "documents" }));
});
test("only navigation metadata is persisted, never document text or form drafts", () => {
  const sessionStorage = storage();
  const navigation = load("navigationState.ts", { window: { sessionStorage } });
  navigation.saveNavigationState({ ...home, view: "documents", selectedDocumentId: "doc-id", content: "private content", draft: "unsaved form" });
  const saved = sessionStorage.getItem(navigation.NAVIGATION_STORAGE_KEY);
  assert.doesNotMatch(saved, /private content|unsaved form|content|draft/);
});
test("fresh App mounts render the saved module instead of the homepage", () => {
  const sessionStorage = storage();
  const navigation = load("navigationState.ts", { window: { sessionStorage } });
  const { default: App } = load("App.tsx", { window: { sessionStorage } });
  for (const [view, title] of [["questions", "AI 面经"], ["documents", "资料库"], ["news", "AI 热点"], ["tags", "标签管理"], ["modules", "模块管理"]]) {
    navigation.saveNavigationState({ ...home, view });
    const html = renderToStaticMarkup(React.createElement(App));
    assert.ok(html.includes(`<h1>${title}</h1>`));
    assert.doesNotMatch(html, /<h1>首页<\/h1>/);
  }
});
test("restoring detail pages show a loading state rather than flashing an empty list", () => {
  const { QuestionsPage, DocumentsPage } = load("App.tsx", {}, "export { QuestionsPage, DocumentsPage };");
  const noop = () => {};
  const questions = renderToStaticMarkup(React.createElement(QuestionsPage, { questions: [], modules: [], tags: [], selected: null, restoring: true, onSelect: noop, onNew: noop, onEdit: noop, onDelete: noop, onSaveFollowUps: noop }));
  const documents = renderToStaticMarkup(React.createElement(DocumentsPage, { documents: [], selected: null, restoring: true, onSelect: noop, onNew: noop, onImport: noop, onEdit: noop, onQuestionsCreated: noop, onReindex: noop, onDelete: noop }));
  assert.match(questions, /正在加载面试题/);
  assert.doesNotMatch(questions, /没有匹配的面试题/);
  assert.match(documents, /正在加载资料/);
  assert.doesNotMatch(documents, /资料库还是空的/);
});
