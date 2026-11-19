import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { after, before, beforeEach, test } from "node:test";
import { fileURLToPath } from "node:url";

// Only connects to the isolated cluster created here, never the application's database.
const root = fileURLToPath(new URL("../../", import.meta.url));
// Keep the Unix socket path below macOS's 103-byte limit.
const temp = mkdtempSync(path.join(tmpdir(), "wb-qid-"));
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
    "-X", "-h", socket, "-p", "55439", "-U", "identity_test", "-d", "postgres",
    "-v", "ON_ERROR_STOP=1", "-Atq",
  ], source, allowFailure);
}

const scalar = (source) => sql(source).stdout.trim();
const rows = (source) => JSON.parse(scalar(`SELECT COALESCE(json_agg(row_to_json(result)), '[]'::json) FROM (${source}) result;`));
const migration = readFileSync(path.join(root, "docs/SQL/面试题自增主键迁移.sql"), "utf8");
const sourceMigration = readFileSync(path.join(root, "docs/SQL/面试题创建来源迁移.sql"), "utf8");
const referencesCheck = readFileSync(path.join(root, "docs/SQL/检查面试题外键引用.sql"), "utf8");
const batchImport = readFileSync(path.join(root, "docs/SQL/导入Agent面试题第一批.sql"), "utf8");
const allSchema = readFileSync(path.join(root, "infra/postgres/init/002-create-business-tables.sql"), "utf8");
const schema = allSchema.slice(0, allSchema.indexOf("CREATE TABLE IF NOT EXISTS rag.document_chunks"));
const legacySchema = schema
  .replace("id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY", "id UUID PRIMARY KEY")
  .replace("question_id BIGINT NOT NULL REFERENCES", "question_id UUID NOT NULL REFERENCES");
const quote = (value) => `'${value.replaceAll("'", "''")}'`;

function setup({ legacy = true } = {}) {
  sql(`CREATE SCHEMA core;\n${legacy ? legacySchema : schema}
    INSERT INTO core.modules (id, name) VALUES ('10000000-0000-0000-0000-000000000001', 'Agent');
    INSERT INTO core.tags (id, name) VALUES
      ('20000000-0000-0000-0000-000000000001', 'Agent'),
      ('20000000-0000-0000-0000-000000000002', 'MCP');`);
}

function seedQuestions() {
  const followUps = JSON.stringify({ version: 1, items: [{ question: "**Follow-up**", answer: "## Answer\n\n- A\n- B" }] });
  sql(`INSERT INTO core.interview_questions
      (id, module_id, question, answer, detailed_answer, follow_up_questions, difficulty, mastery_status, created_at, updated_at)
    VALUES
      ('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001',
       'Third', 'Third answer', NULL, NULL, 'HARD', 'MASTERED', '2026-11-03', '2026-11-05'),
      ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
       'Second', 'Second answer', NULL, '', 'EASY', 'LEARNING', '2026-11-01', '2026-11-06'),
      ('30000000-0000-0000-0000-000000000001', NULL,
       'First', ${quote("**Markdown**\n\nO'Reilly \\\\ code")}, 'Explanation', ${quote(followUps)},
       'MEDIUM', 'NOT_STARTED', '2026-11-01', '2026-11-07');
    INSERT INTO core.interview_question_tags (question_id, tag_id)
    VALUES ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001'),
           ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002'),
           ('30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000002');`);
}

function seedLegacyReferences() {
  sql(`CREATE TABLE core.ai_question_drafts (
      id UUID PRIMARY KEY,
      question TEXT NOT NULL,
      accepted_question_id UUID REFERENCES core.interview_questions(id) ON DELETE SET NULL
    );
    CREATE INDEX draft_accepted_question_idx ON core.ai_question_drafts(accepted_question_id);
    CREATE TABLE core.interview_question_sources (
      id UUID PRIMARY KEY,
      question_id UUID NOT NULL REFERENCES core.interview_questions(id) ON DELETE CASCADE,
      source_name TEXT NOT NULL,
      UNIQUE (question_id, source_name)
    );
    ALTER TABLE core.interview_question_sources ALTER CONSTRAINT
      interview_question_sources_question_id_fkey DEFERRABLE INITIALLY DEFERRED;
    COMMENT ON CONSTRAINT ai_question_drafts_accepted_question_id_fkey ON core.ai_question_drafts
      IS 'Preserve accepted draft linkage';
    INSERT INTO core.ai_question_drafts (id, question, accepted_question_id) VALUES
      ('40000000-0000-0000-0000-000000000001', 'Accepted first', '30000000-0000-0000-0000-000000000001'),
      ('40000000-0000-0000-0000-000000000002', 'Accepted third', '30000000-0000-0000-0000-000000000003'),
      ('40000000-0000-0000-0000-000000000003', 'Not accepted', NULL);
    INSERT INTO core.interview_question_sources (id, question_id, source_name) VALUES
      ('50000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'First source'),
      ('50000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000003', 'Third source');`);
}

function snapshotDrafts() {
  return rows(`SELECT draft.id, draft.question, q.question AS accepted_question
    FROM core.ai_question_drafts draft LEFT JOIN core.interview_questions q ON q.id = draft.accepted_question_id
    ORDER BY draft.id`);
}

function snapshotSources() {
  return rows(`SELECT source.id, source.source_name, q.question
    FROM core.interview_question_sources source JOIN core.interview_questions q ON q.id = source.question_id
    ORDER BY source.id`);
}

function snapshotConstraints() {
  return rows(`SELECT conrelid::regclass::text AS table_name, conname, pg_get_constraintdef(oid) AS definition,
    obj_description(oid, 'pg_constraint') AS description
    FROM pg_constraint WHERE conrelid IN ('core.ai_question_drafts'::regclass,
      'core.interview_question_sources'::regclass, 'core.interview_question_tags'::regclass)
    ORDER BY conrelid, conname`);
}

function snapshotQuestions() {
  return rows(`SELECT question, module_id, answer, detailed_answer, follow_up_questions,
    difficulty, mastery_status, created_at, updated_at FROM core.interview_questions ORDER BY question`);
}

function snapshotTags() {
  return rows(`SELECT q.question, tags.tag_id FROM core.interview_question_tags tags
    JOIN core.interview_questions q ON q.id = tags.question_id ORDER BY q.question, tags.tag_id`);
}

function assertIdentity() {
  assert.equal(scalar(`SELECT data_type || ':' || is_identity || ':' || identity_generation
    FROM information_schema.columns WHERE table_schema = 'core'
    AND table_name = 'interview_questions' AND column_name = 'id'`), "bigint:YES:ALWAYS");
  assert.equal(scalar(`SELECT data_type FROM information_schema.columns WHERE table_schema = 'core'
    AND table_name = 'interview_question_tags' AND column_name = 'question_id'`), "bigint");
  assert.equal(scalar(`SELECT count(*) FROM information_schema.columns WHERE table_schema = 'core'
    AND table_name IN ('interview_questions', 'interview_question_tags')
    AND column_name IN ('identity_id', 'identity_question_id', 'old_id')`), "0");
}

before(() => {
  run("initdb", ["-D", data, "--no-locale", "--encoding=UTF8", "--auth=trust", "-U", "identity_test"]);
  run("pg_ctl", ["-D", data, "-l", path.join(temp, "postgres.log"), "-o", `-h '' -k ${socket} -p 55439 -F`, "-w", "start"]);
  started = true;
});

beforeEach(() => { sql("DROP SCHEMA IF EXISTS core CASCADE;"); });

after(() => {
  if (started) run("pg_ctl", ["-D", data, "-m", "fast", "-w", "stop"]);
  console.log(`Isolated PostgreSQL test files: ${temp}`);
});

test("migration replaces UUIDs with identity IDs while preserving all content and relationships", () => {
  setup();
  seedQuestions();
  const questions = snapshotQuestions();
  const tags = snapshotTags();
  sql(migration);
  assertIdentity();
  assert.deepEqual(snapshotQuestions(), questions);
  assert.deepEqual(snapshotTags(), tags);
  assert.deepEqual(rows("SELECT id, question FROM core.interview_questions ORDER BY id"), [
    { id: 1, question: "First" }, { id: 2, question: "Second" }, { id: 3, question: "Third" },
  ]);
  assert.equal(scalar("SELECT to_regclass('pg_temp.question_id_mapping') IS NULL"), "t");
  assert.equal(scalar("INSERT INTO core.interview_questions (question, answer) VALUES ('Fourth', 'Answer') RETURNING id"), "4");
  sql("UPDATE core.interview_questions SET answer = 'Edited' WHERE id = 1;");
  assert.equal(scalar("SELECT question FROM core.interview_questions ORDER BY id LIMIT 1"), "First");
  sql("DELETE FROM core.interview_questions WHERE id = 1;");
  assert.equal(scalar("SELECT count(*) FROM core.interview_question_tags WHERE question_id = 1"), "0");
  assert.notEqual(sql(`INSERT INTO core.interview_question_tags (question_id, tag_id)
    VALUES (99, '20000000-0000-0000-0000-000000000001');`, true).status, 0);
  assert.notEqual(sql("INSERT INTO core.interview_questions (id, question, answer) VALUES (99, 'Q', 'A');", true).status, 0);
});

test("repeated migration does not renumber IDs or reset the sequence", () => {
  setup();
  seedQuestions();
  sql(migration);
  sql("DELETE FROM core.interview_questions WHERE id = 2;");
  const questions = rows("SELECT id, question FROM core.interview_questions ORDER BY id");
  sql(migration);
  assert.deepEqual(rows("SELECT id, question FROM core.interview_questions ORDER BY id"), questions);
  assert.equal(scalar("INSERT INTO core.interview_questions (question, answer) VALUES ('Next', 'A') RETURNING id"), "4");
});

test("empty legacy tables migrate and start numbering at 1", () => {
  setup();
  sql(migration);
  assertIdentity();
  assert.equal(scalar("INSERT INTO core.interview_questions (question, answer) VALUES ('First', 'A') RETURNING id"), "1");
});

test("legacy draft and source references preserve data, nullable links, indexes, constraints and delete actions", () => {
  setup();
  seedQuestions();
  seedLegacyReferences();
  const questions = snapshotQuestions();
  const tags = snapshotTags();
  const drafts = snapshotDrafts();
  const sources = snapshotSources();
  const constraints = snapshotConstraints();
  const indexes = rows("SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = 'core' ORDER BY tablename, indexname");
  const references = rows(referencesCheck.replace(/^--.*$/gm, "").trim().replace(/;$/, ""));
  assert.equal(references.length, 3);
  assert.ok(references.every((row) => row.migration_status === "handled_by_current_migration"));

  sql(migration);
  assertIdentity();
  assert.deepEqual(snapshotQuestions(), questions);
  assert.deepEqual(snapshotTags(), tags);
  assert.deepEqual(snapshotDrafts(), drafts);
  assert.deepEqual(snapshotSources(), sources);
  assert.deepEqual(snapshotConstraints(), constraints);
  assert.deepEqual(rows("SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = 'core' ORDER BY tablename, indexname"), indexes);
  assert.equal(scalar(`SELECT data_type FROM information_schema.columns WHERE table_schema = 'core'
    AND table_name = 'ai_question_drafts' AND column_name = 'accepted_question_id'`), "bigint");
  assert.equal(scalar(`SELECT data_type FROM information_schema.columns WHERE table_schema = 'core'
    AND table_name = 'interview_question_sources' AND column_name = 'question_id'`), "bigint");
  assert.equal(scalar("SELECT accepted_question_id FROM core.ai_question_drafts WHERE question = 'Accepted first'"), "1");
  assert.equal(scalar("SELECT count(*) FROM core.ai_question_drafts WHERE accepted_question_id IS NULL"), "1");
  sql(migration);
  assert.deepEqual(snapshotDrafts(), drafts);
  assert.deepEqual(snapshotSources(), sources);
  assert.equal(scalar("INSERT INTO core.interview_questions (question, answer) VALUES ('Next', 'A') RETURNING id"), "4");

  sql("DELETE FROM core.interview_questions WHERE id = 1;");
  assert.equal(scalar("SELECT count(*) FROM core.interview_question_tags WHERE question_id = 1"), "0");
  assert.equal(scalar("SELECT count(*) FROM core.interview_question_sources WHERE question_id = 1"), "0");
  assert.equal(scalar("SELECT count(*) FROM core.ai_question_drafts"), "3");
  assert.equal(scalar("SELECT accepted_question_id IS NULL FROM core.ai_question_drafts WHERE question = 'Accepted first'"), "t");
  assert.equal(scalar("SELECT accepted_question_id FROM core.ai_question_drafts WHERE question = 'Accepted third'"), "3");
});

test("a database with only one optional legacy table also migrates", () => {
  setup();
  seedQuestions();
  seedLegacyReferences();
  sql("DROP TABLE core.interview_question_sources;");
  const drafts = snapshotDrafts();
  sql(migration);
  assertIdentity();
  assert.deepEqual(snapshotDrafts(), drafts);
  assert.equal(scalar("SELECT to_regclass('core.interview_question_sources') IS NULL"), "t");
});

test("unmapped legacy references abort and roll back instead of silently turning into NULL", () => {
  setup();
  seedQuestions();
  seedLegacyReferences();
  sql(`ALTER TABLE core.ai_question_drafts DROP CONSTRAINT ai_question_drafts_accepted_question_id_fkey;
    UPDATE core.ai_question_drafts SET accepted_question_id = '30000000-0000-0000-0000-000000000099'
      WHERE question = 'Accepted first';
    ALTER TABLE core.ai_question_drafts ADD CONSTRAINT ai_question_drafts_accepted_question_id_fkey
      FOREIGN KEY (accepted_question_id) REFERENCES core.interview_questions(id) ON DELETE SET NULL NOT VALID;`);
  const questions = snapshotQuestions();
  const tags = snapshotTags();
  const sources = snapshotSources();
  const failure = sql(migration, true);
  assert.notEqual(failure.status, 0);
  assert.match(failure.stderr, /Unmapped question reference/);
  assert.equal(scalar("SELECT accepted_question_id FROM core.ai_question_drafts WHERE question = 'Accepted first'"),
    "30000000-0000-0000-0000-000000000099");
  assert.deepEqual(snapshotQuestions(), questions);
  assert.deepEqual(snapshotTags(), tags);
  assert.deepEqual(snapshotSources(), sources);
});

test("unknown referencing tables cause a safe failure without changing data", () => {
  setup();
  seedQuestions();
  sql("CREATE TABLE core.extra_links (question_id UUID REFERENCES core.interview_questions(id));");
  const before = snapshotQuestions();
  const references = rows(referencesCheck.replace(/^--.*$/gm, "").trim().replace(/;$/, ""));
  assert.equal(references.find((row) => row.table_name === "extra_links").migration_status, "requires_migration_plan");
  assert.equal(references.find((row) => row.table_name === "interview_question_tags").migration_status, "handled_by_current_migration");
  const failure = sql(migration, true);
  assert.notEqual(failure.status, 0);
  assert.match(failure.stderr, /Additional tables reference/);
  assert.match(failure.stderr, /core\.extra_links \[extra_links_question_id_fkey\]: FOREIGN KEY/);
  assert.deepEqual(snapshotQuestions(), before);
  assert.equal(scalar(`SELECT data_type FROM information_schema.columns WHERE table_schema = 'core'
    AND table_name = 'interview_questions' AND column_name = 'id'`), "uuid");
});

test("extra references in the tag table are diagnosed without dropping their constraints", () => {
  setup();
  seedQuestions();
  sql(`ALTER TABLE core.interview_question_tags ADD COLUMN other_question_id UUID;
    ALTER TABLE core.interview_question_tags ADD CONSTRAINT other_question_link
      FOREIGN KEY (other_question_id) REFERENCES core.interview_questions(id);`);
  const before = snapshotTags();
  const failure = sql(migration, true);
  assert.notEqual(failure.status, 0);
  assert.match(failure.stderr, /core\.interview_question_tags \[other_question_link\]/);
  assert.deepEqual(snapshotTags(), before);
  assert.equal(scalar(`SELECT count(*) FROM pg_constraint
    WHERE conrelid = 'core.interview_question_tags'::regclass AND conname = 'other_question_link'`), "1");
});

test("a dependency failure partway through rolls back schema, rows, and tag relations", () => {
  setup();
  seedQuestions();
  sql("CREATE VIEW core.saved_question_ids AS SELECT id FROM core.interview_questions;");
  const questions = snapshotQuestions();
  const tags = snapshotTags();
  assert.notEqual(sql(migration, true).status, 0);
  assert.deepEqual(snapshotQuestions(), questions);
  assert.deepEqual(snapshotTags(), tags);
  assert.equal(scalar(`SELECT count(*) FROM information_schema.columns WHERE table_schema = 'core'
    AND table_name IN ('interview_questions', 'interview_question_tags')
    AND column_name IN ('identity_id', 'identity_question_id')`), "0");
});

test("fresh schema and batch import use identity IDs in source order and skip repeated imports", () => {
  setup({ legacy: false });
  sql(migration);
  sql(batchImport);
  assertIdentity();
  assert.equal(scalar("SELECT count(*) FROM core.interview_questions WHERE difficulty = 'EASY' AND mastery_status = 'NOT_STARTED'"), "24");
  assert.equal(scalar("SELECT count(*) FROM core.interview_questions WHERE source_type = 'MANUAL'"), "24");
  assert.equal(scalar("SELECT sum(jsonb_array_length(follow_up_questions::jsonb->'items')) FROM core.interview_questions"), "79");
  assert.equal(scalar("SELECT count(*) FROM core.interview_question_tags"), "48");
  const payload = JSON.parse(batchImport.match(/\$interview_payload\$\n([\s\S]*?)\n\$interview_payload\$::JSONB/)[1]);
  const imported = rows('SELECT id, question, answer, follow_up_questions AS "followUpQuestions" FROM core.interview_questions ORDER BY id');
  assert.deepEqual(imported, payload.map((item, index) => ({ id: index + 1, ...item })));
  sql("UPDATE core.interview_questions SET answer = 'User edit', mastery_status = 'MASTERED' WHERE id = 1;");
  sql(batchImport);
  assert.equal(scalar("SELECT count(*) FROM core.interview_questions"), "24");
  assert.equal(scalar("SELECT answer FROM core.interview_questions WHERE id = 1"), "User edit");
  assert.equal(scalar("INSERT INTO core.interview_questions (question, answer) VALUES ('Next', 'A') RETURNING id"), "25");
});

test("source migration marks old questions manual and preserves generated sources on repeated runs", () => {
  setup({ legacy: false });
  sql(`ALTER TABLE core.interview_questions DROP COLUMN source_type;
    INSERT INTO core.interview_questions (question, answer) VALUES ('Existing', 'Answer');`);
  const before = snapshotQuestions();
  sql(sourceMigration);
  assert.deepEqual(snapshotQuestions(), before);
  assert.equal(scalar("SELECT source_type FROM core.interview_questions WHERE question = 'Existing'"), "MANUAL");
  sql(`INSERT INTO core.interview_questions (question, answer, source_type) VALUES ('Generated', 'Answer', 'DOCUMENT');`);
  sql(sourceMigration);
  assert.equal(scalar("SELECT source_type FROM core.interview_questions WHERE question = 'Generated'"), "DOCUMENT");
  assert.equal(scalar("INSERT INTO core.interview_questions (question, answer) VALUES ('Manual', 'Answer') RETURNING source_type"), "MANUAL");
  assert.notEqual(sql("INSERT INTO core.interview_questions (question, answer, source_type) VALUES ('Q', 'A', 'OTHER');", true).status, 0);
  assert.notEqual(sql("INSERT INTO core.interview_questions (question, answer, source_type) VALUES ('Q', 'A', NULL);", true).status, 0);
});

test("fresh schema supports both sources and enforces manual default and source constraints", () => {
  setup({ legacy: false });
  sql(sourceMigration);
  assert.equal(scalar("INSERT INTO core.interview_questions (question, answer) VALUES ('Q', 'A') RETURNING source_type"), "MANUAL");
  assert.equal(scalar("INSERT INTO core.interview_questions (question, answer, source_type) VALUES ('Q', 'A', 'DOCUMENT') RETURNING source_type"), "DOCUMENT");
  assert.notEqual(sql("UPDATE core.interview_questions SET source_type = 'OTHER';", true).status, 0);
  assert.notEqual(sql("UPDATE core.interview_questions SET source_type = NULL;", true).status, 0);
});
