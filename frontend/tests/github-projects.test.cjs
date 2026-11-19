const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const vm = require("node:vm");
const { buildSync } = require("esbuild");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const root = path.resolve(__dirname, "..");
const bundle = buildSync({
  stdin: {
    contents: `${readFileSync(path.join(root, "src/GithubProjects.tsx"), "utf8")}\nexport { GithubProjectRow, GithubDetail, GithubProjectModal };`,
    resolveDir: path.join(root, "src"), loader: "tsx",
  },
  bundle: true, write: false, platform: "node", format: "cjs",
  external: ["react"], define: { "import.meta.env": "{}" },
});
function load(globals = {}) {
  const module = { exports: {} };
  vm.runInNewContext(bundle.outputFiles[0].text, { module, exports: module.exports, require, React, ...globals });
  return module.exports;
}
const noop = () => {};
const project = {
  id: "9007199254740993", category: "PERSONAL_DEMO", fullName: "owner/project",
  repositoryUrl: "https://github.com/owner/project", githubRepoId: "123",
  description: "An agent project", language: "Java", stars: 1200, forks: 30, topics: ["agent"],
  archived: false, pushedAt: "2026-11-08T00:00:00Z", notes: "# Notes\n\n- **Important**",
  syncStatus: "SUCCESS", syncError: null, lastSyncedAt: "2026-11-08T00:00:00Z",
};
const render = (component, props) => renderToStaticMarkup(React.createElement(component, props));
const { default: GithubProjects, GithubProjectRow, GithubDetail, GithubProjectModal } = load();

test("GitHub has the exact confirmed category names and no requests during rendering", () => {
  const html = render(GithubProjects, { category: "LEARNING", selectedId: null, refreshKey: 0, onNavigate: noop });
  assert.match(html, /自研/); assert.match(html, /开源/);
  assert.doesNotMatch(html, /我的 Demo|学习收藏/);
  assert.match(html, /aria-selected="true"[^>]*>开源/);
});
test("rows show metadata and safe external links without exposing personal notes", () => {
  const html = render(GithubProjectRow, { project, busy: false, onOpen: noop, onSync: noop, onEdit: noop, onDelete: noop });
  for (const value of ["owner/project", "An agent project", "Java", 'href="https://github.com/owner/project"', 'rel="noopener noreferrer"']) assert.ok(html.includes(value));
  assert.doesNotMatch(html, /Important|<textarea/);
  const unsafe = render(GithubProjectRow, { project: { ...project, repositoryUrl: "javascript:alert(1)", description: "<script>bad</script>" }, busy: false });
  assert.doesNotMatch(unsafe, /href="javascript:|<script>/);
});
test("detail renders Markdown notes and exposes sync failures without losing the overview", () => {
  const html = render(GithubDetail, { project: { ...project, syncStatus: "FAILED", syncError: "Network unavailable" }, busy: false });
  assert.match(html, /<strong>Important<\/strong>/);
  assert.match(html, /Network unavailable/);
  assert.match(html, /An agent project/);
  assert.match(html, /预览/); assert.match(html, /原文/);
});
test("new modal defaults to current category and edit keeps the repository read-only", () => {
  const fresh = render(GithubProjectModal, { value: "new", category: "LEARNING", onClose: noop, onSaved: noop });
  assert.match(fresh, /value="LEARNING" selected/);
  const edit = render(GithubProjectModal, { value: project, category: "LEARNING", onClose: noop, onSaved: noop });
  assert.match(edit, /value="PERSONAL_DEMO" selected/);
  assert.match(edit, /readOnly=""/);
});
test("restoring a project shows loading instead of an empty list", () => {
  const html = render(GithubProjects, { category: "LEARNING", selectedId: project.id, refreshKey: 0, onNavigate: noop });
  assert.match(html, /正在加载项目/); assert.doesNotMatch(html, /暂无开源项目/);
});

function findElement(element, predicate) {
  if (!React.isValidElement(element)) return undefined;
  if (predicate(element)) return element;
  for (const child of React.Children.toArray(element.props.children)) {
    const found = findElement(child, predicate);
    if (found) return found;
  }
}
function hookModule() {
  const effects = [], requests = [];
  const hooks = { ...React, useState: (value) => [typeof value === "function" ? value() : value, noop],
    useEffect: (effect) => effects.push(effect), useRef: (value) => ({ current: value }) };
  const exports = load({
    React: hooks, FormData, AbortController,
    require: (name) => name === "react" ? hooks : require(name),
    fetch: async (url, options) => {
      requests.push({ url, ...options });
      return { ok: true, status: 200, json: async () => url.endsWith("/counts") ? { personalDemo: 1, learning: 2 } : url.includes("?") ? { items: [], total: 0 } : project };
    },
  });
  return { ...exports, effects, requests };
}
test("page queries category on the backend and never contacts GitHub from the browser", async () => {
  const { default: Component, effects, requests } = hookModule();
  Component({ category: "LEARNING", selectedId: null, refreshKey: 0, onNavigate: noop });
  const cleanup = effects[1]();
  await new Promise(setImmediate);
  assert.equal(requests.length, 2);
  assert.ok(requests[0].url.includes("/github-projects?category=LEARNING"));
  assert.ok(requests.every((request) => !request.url.includes("api.github.com")));
  cleanup();
});
test("new projects send the selected category and edits never replace remote metadata", async () => {
  for (const value of ["new", project]) {
    const { GithubProjectModal: Component, requests } = hookModule();
    let saved = false;
    const element = Component({ value, category: "LEARNING", onClose: noop, onSaved: () => { saved = true; } });
    findElement(element, (node) => node.type === "form").props.onSubmit({ preventDefault: noop });
    await new Promise(setImmediate);
    assert.equal(saved, true);
    const body = JSON.parse(requests[0].body);
    if (value === "new") {
      assert.equal(requests[0].method, "POST");
      assert.equal(body.category, "LEARNING");
    } else {
      assert.equal(requests[0].method, "PATCH");
      assert.equal(body.category, "PERSONAL_DEMO");
      assert.equal(body.notes, project.notes);
      assert.deepEqual(Object.keys(body).sort(), ["category", "notes"]);
    }
  }
});
