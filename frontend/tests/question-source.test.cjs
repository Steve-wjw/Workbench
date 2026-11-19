const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const vm = require("node:vm");
const { buildSync } = require("esbuild");
const React = require("react");

const root = path.resolve(__dirname, "..");
const result = buildSync({
  stdin: {
    contents: `${readFileSync(path.join(root, "src/App.tsx"), "utf8")}\nexport { QuestionModal, GeneratedQuestionsModal };`,
    resolveDir: path.join(root, "src"), loader: "tsx",
  },
  bundle: true, write: false, platform: "node", format: "cjs",
  external: ["react"], define: { "import.meta.env": "{}" },
});

// Exercise the real save handlers with initial hook values, without a DOM or live API.
function loadModals() {
  const requests = [];
  const hookReact = { ...React, useState: (value) => [typeof value === "function" ? value() : value, () => {}] };
  const module = { exports: {} };
  vm.runInNewContext(result.outputFiles[0].text, {
    module, exports: module.exports, React: hookReact, FormData,
    require: (name) => name === "react" ? hookReact : require(name),
    fetch: async (url, options) => {
      requests.push({ url, method: options.method, body: JSON.parse(options.body) });
      return { ok: true, status: 201, json: async () => ({}) };
    },
  });
  return { ...module.exports, requests };
}

function findElement(element, predicate) {
  if (!React.isValidElement(element)) return undefined;
  if (predicate(element)) return element;
  for (const child of React.Children.toArray(element.props.children)) {
    const match = findElement(child, predicate);
    if (match) return match;
  }
}

const noop = () => {};
test("manual creation sends MANUAL and editing omits the source field", async () => {
  for (const value of ["new", {
    id: "42", question: "Question", answer: "Answer", tags: [],
    difficulty: "EASY", masteryStatus: "NOT_STARTED", sourceType: "DOCUMENT",
  }]) {
    const { QuestionModal, requests } = loadModals();
    let saved = false;
    const element = QuestionModal({
      value, modules: [], tags: [], onCreateModule: noop, onCreateTag: noop,
      onClose: noop, onSaved: async () => { saved = true; },
    });
    findElement(element, (node) => node.type === "form").props.onSubmit({ preventDefault: noop });
    await new Promise(setImmediate);
    assert.equal(saved, true);
    assert.equal(requests.length, 1);
    if (value === "new") {
      assert.equal(requests[0].method, "POST");
      assert.equal(requests[0].body.sourceType, "MANUAL");
    } else {
      assert.equal(requests[0].method, "PATCH");
      assert.ok(requests[0].url.endsWith("/interview-questions/42"));
      assert.equal(Object.hasOwn(requests[0].body, "sourceType"), false);
    }
  }
});

test("every confirmed document-generated question is saved with DOCUMENT source", async () => {
  const { GeneratedQuestionsModal, requests } = loadModals();
  let saved = false;
  const element = GeneratedQuestionsModal({
    document: { title: "Agent", moduleId: "module-1", tags: [{ id: "tag-1" }] },
    questions: [
      { question: "First", answer: "Answer", difficulty: "EASY" },
      { question: "Second", answer: "Answer", difficulty: "MEDIUM" },
    ],
    onClose: noop, onSaved: async () => { saved = true; },
  });
  findElement(element, (node) => node.type === "button" && node.props.className === "primary-button").props.onClick();
  await new Promise(setImmediate);
  assert.equal(saved, true);
  assert.equal(requests.length, 2);
  for (const request of requests) {
    assert.equal(request.method, "POST");
    assert.ok(request.url.endsWith("/interview-questions"));
    assert.equal(request.body.sourceType, "DOCUMENT");
    assert.equal(request.body.moduleId, "module-1");
    assert.deepEqual(request.body.tagIds, ["tag-1"]);
  }
});
