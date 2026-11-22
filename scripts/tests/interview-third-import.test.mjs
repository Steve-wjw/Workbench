import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { after, before, beforeEach, test } from "node:test";
import { fileURLToPath } from "node:url";

// Only connect to this isolated Unix-socket cluster, never the application's database.
const root = fileURLToPath(new URL("../../", import.meta.url));
const temp = mkdtempSync(path.join(tmpdir(), "wb-i3-"));
const data = path.join(temp, "data");
const socket = path.join(temp, "socket");
mkdirSync(socket);
let started = false;

function run(program, args, input, allowFailure = false) {
  const result = spawnSync(program, args, { input, encoding: "utf8" });
  if (!allowFailure && result.status !== 0) {
    throw new Error(`${program}: ${result.error ?? ""}\n${result.stderr}\n${result.stdout}`);
  }
  return result;
}

function sql(source, allowFailure = false) {
  return run("psql", [
    "-X", "-h", socket, "-p", "55439", "-U", "interview3_test", "-d", "postgres",
    "-v", "ON_ERROR_STOP=1", "-Atq",
  ], source, allowFailure);
}

const scalar = (source) => sql(source).stdout.trim();
const rows = (source) => JSON.parse(scalar(
  `SELECT COALESCE(json_agg(row_to_json(result)), '[]'::json) FROM (${source}) result;`,
));
const quote = (value) => `'${value.replaceAll("'", "''")}'`;
const importSql = readFileSync(path.join(root, "docs/V1/SQL/导入Agent面试题第三批.sql"), "utf8");
const payloadParts = importSql.split("$interview3_payload$");
assert.equal(payloadParts.length, 3);
const payload = JSON.parse(payloadParts[1]);
const allSchema = readFileSync(path.join(root, "infra/postgres/init/002-create-business-tables.sql"), "utf8");
const schema = allSchema.slice(0, allSchema.indexOf("CREATE TABLE IF NOT EXISTS rag.document_chunks"));
const moduleId = "10000000-0000-0000-0000-000000000001";
const addModule = (name = "Agent", status = "ACTIVE") => sql(
  `INSERT INTO core.modules (id, name, status) VALUES (${quote(moduleId)}, ${quote(name)}, ${quote(status)});`,
);

before(() => {
  run("initdb", ["-D", data, "--no-locale", "--encoding=UTF8", "--auth=trust", "-U", "interview3_test"]);
  run("pg_ctl", [
    "-D", data, "-l", path.join(temp, "postgres.log"),
    "-o", `-h '' -k ${socket} -p 55439 -F`, "-w", "start",
  ]);
  started = true;
});

beforeEach(() => {
  sql(`DROP SCHEMA IF EXISTS core CASCADE; CREATE SCHEMA core;\n${schema}`);
});

after(() => {
  if (started) run("pg_ctl", ["-D", data, "-m", "fast", "-w", "stop"]);
  console.log(`Isolated PostgreSQL test files: ${temp}`);
});

test("imports all content, structured follow-ups, heading difficulty and exact tags in document order", () => {
  addModule(" aGeNt ");
  sql("INSERT INTO core.tags (id, name) VALUES (gen_random_uuid(), ' 大模型 '), (gen_random_uuid(), 'ai');");
  const result = sql(importSql);
  assert.match(result.stderr, /Inserted: 10, skipped: 0, tags created: 10, tag relations inserted: 32/);
  const imported = rows(`SELECT id, question, answer, follow_up_questions AS "followUpQuestions", difficulty,
    module_id, detailed_answer, mastery_status, source_type FROM core.interview_questions ORDER BY id`);
  assert.equal(imported.length, 10);
  assert.deepEqual(imported.map((item) => item.id), Array.from({ length: 10 }, (_, index) => index + 1));
  for (const [index, item] of imported.entries()) {
    assert.deepEqual(
      { question: item.question, answer: item.answer, followUpQuestions: item.followUpQuestions, difficulty: item.difficulty },
      { question: payload[index].question, answer: payload[index].answer,
        followUpQuestions: payload[index].followUpQuestions, difficulty: payload[index].difficulty },
    );
    assert.equal(item.module_id, moduleId);
    assert.equal(item.detailed_answer, null);
    assert.equal(item.mastery_status, "NOT_STARTED");
    assert.equal(item.source_type, "MANUAL");
    const assigned = rows(`SELECT lower(btrim(t.name)) AS name FROM core.interview_question_tags qt
      JOIN core.tags t ON t.id = qt.tag_id WHERE qt.question_id = ${item.id} ORDER BY lower(btrim(t.name))`);
    assert.deepEqual(assigned.map((tag) => tag.name).sort(), payload[index].tags.map((tag) => tag.toLowerCase()).sort());
  }
  assert.equal(imported.reduce((count, item) => count + (JSON.parse(item.followUpQuestions)?.items.length ?? 0), 0), 28);
  assert.deepEqual(
    ["EASY", "MEDIUM", "HARD"].map((difficulty) => imported.filter((item) => item.difficulty === difficulty).length),
    [3, 5, 2],
  );
  assert.equal(scalar("SELECT count(*) FROM core.tags"), "12");
  assert.equal(scalar("SELECT count(*) FROM core.interview_question_tags"), "32");
  assert.equal(imported[8].followUpQuestions, null);
  assert.match(JSON.parse(imported[1].followUpQuestions).items[0].answer,
    /^详细解答可以看：A2A 协议的工作流程是怎样的？\n\nTask 状态/);
  assert.match(imported[7].answer, /`@Tool`/);
  assert.match(imported[1].answer, /\*\*Agent Card\*\*/);
  assert.equal(scalar("SELECT count(*) FROM core.documents"), "0");
  assert.equal(scalar("SELECT count(*) FROM core.index_jobs"), "0");
});

test("rerunning skips all questions without modifying content, tag relations or the identity sequence", () => {
  addModule();
  sql(importSql);
  const before = scalar("SELECT json_agg(q ORDER BY id)::text FROM core.interview_questions q");
  const result = sql(importSql);
  assert.match(result.stderr, /Inserted: 0, skipped: 10, tags created: 0, tag relations inserted: 0/);
  assert.equal(scalar("SELECT json_agg(q ORDER BY id)::text FROM core.interview_questions q"), before);
  assert.equal(scalar("SELECT count(*) FROM core.tags"), "12");
  assert.equal(scalar("SELECT count(*) FROM core.interview_question_tags"), "32");
  assert.equal(scalar("INSERT INTO core.interview_questions (question, answer) VALUES ('Next', 'Answer') RETURNING id"), "11");
});

test("existing same-module title is skipped, preserving its content, difficulty, progress and tags", () => {
  addModule();
  sql(`INSERT INTO core.interview_questions (module_id, question, answer, difficulty, mastery_status, source_type)
    VALUES (${quote(moduleId)}, ${quote(` ${payload[0].question} `)}, 'Keep my answer', 'EASY', 'MASTERED', 'DOCUMENT');
    INSERT INTO core.tags (id, name) VALUES ('20000000-0000-0000-0000-000000000001', 'Existing-only');
    INSERT INTO core.interview_question_tags (question_id, tag_id)
    VALUES (1, '20000000-0000-0000-0000-000000000001');`);
  const before = scalar("SELECT row_to_json(q)::text FROM core.interview_questions q WHERE id = 1");
  const result = sql(importSql);
  assert.match(result.stderr, /Inserted: 9, skipped: 1/);
  assert.equal(scalar("SELECT row_to_json(q)::text FROM core.interview_questions q WHERE id = 1"), before);
  assert.equal(scalar("SELECT count(*) FROM core.interview_question_tags WHERE question_id = 1"), "1");
  assert.equal(scalar("SELECT count(*) FROM core.tags WHERE name IN ('AI Agent', 'LangChain', 'AutoGPT')"), "0");
});

test("the same title in a different module does not block importing into Agent", () => {
  addModule();
  sql(`INSERT INTO core.modules (id, name) VALUES ('10000000-0000-0000-0000-000000000002', 'Other');
    INSERT INTO core.interview_questions (module_id, question, answer)
    VALUES ('10000000-0000-0000-0000-000000000002', ${quote(payload[0].question)}, 'Other answer');`);
  sql(importSql);
  assert.equal(scalar("SELECT count(*) FROM core.interview_questions"), "11");
  assert.equal(scalar(`SELECT count(*) FROM core.interview_questions WHERE module_id = ${quote(moduleId)}`), "10");
});

for (const mode of ["missing", "archived", "duplicate"]) {
  test(`${mode} Agent module cancels the import without inserting content or tags`, () => {
    if (mode === "archived") addModule("Agent", "ARCHIVED");
    if (mode === "duplicate") {
      addModule();
      sql("INSERT INTO core.modules (id, name) VALUES (gen_random_uuid(), ' agent ');");
    }
    const result = sql(importSql, true);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, mode === "duplicate" ? /Multiple active Agent modules/ : /No active Agent module/);
    assert.equal(scalar("SELECT count(*) FROM core.interview_questions"), "0");
    assert.equal(scalar("SELECT count(*) FROM core.tags"), "0");
  });
}

test("ambiguous existing tag names roll back earlier question and tag inserts", () => {
  addModule();
  sql("INSERT INTO core.tags (id, name) VALUES (gen_random_uuid(), 'A2A'), (gen_random_uuid(), ' a2a ');");
  const result = sql(importSql, true);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Multiple existing tags match "A2A"/);
  assert.equal(scalar("SELECT count(*) FROM core.interview_questions"), "0");
  assert.equal(scalar("SELECT count(*) FROM core.interview_question_tags"), "0");
  assert.equal(scalar("SELECT count(*) FROM core.tags"), "2");
});
