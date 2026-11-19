import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { before, after, beforeEach, test } from "node:test";
import { fileURLToPath } from "node:url";

// Only uses a new isolated local cluster; never reads .env or connects to the business database.
const root = fileURLToPath(new URL("../../", import.meta.url));
const temp = mkdtempSync(path.join(tmpdir(), "workbench-github-test-"));
const socket = path.join(temp, "socket");
mkdirSync(socket);
let started = false;
function run(command, args, input, allowFailure = false) {
  const result = spawnSync(command, args, { input, encoding: "utf8" });
  if (!allowFailure && result.status !== 0) throw new Error(`${command}: ${result.error ?? ""}\n${result.stderr}`);
  return result;
}
function sql(input, allowFailure = false) {
  return run("psql", ["-X", "-h", socket, "-p", "55441", "-U", "github_test", "-d", "postgres",
    "-v", "ON_ERROR_STOP=1", "-Atq"], input, allowFailure);
}
const scalar = (input) => sql(input).stdout.trim();
const migration = readFileSync(path.join(root, "docs/SQL/GitHub项目建表.sql"), "utf8");
const schema = readFileSync(path.join(root, "infra/postgres/init/002-create-business-tables.sql"), "utf8");
const initBlock = schema.slice(schema.indexOf("CREATE TABLE IF NOT EXISTS core.github_projects ("), schema.indexOf("CREATE TABLE IF NOT EXISTS rag.document_chunks"));

before(() => {
  run("initdb", ["-D", path.join(temp, "data"), "--no-locale", "--encoding=UTF8", "--auth=trust", "-U", "github_test"]);
  run("pg_ctl", ["-D", path.join(temp, "data"), "-l", path.join(temp, "postgres.log"),
    "-o", `-h '' -k ${socket} -p 55441 -F`, "-w", "start"]);
  started = true;
});
beforeEach(() => sql("DROP SCHEMA IF EXISTS core CASCADE; CREATE SCHEMA core;"));
after(() => {
  if (started) run("pg_ctl", ["-D", path.join(temp, "data"), "-m", "fast", "-w", "stop"]);
  console.log(`Isolated test artifacts: ${temp}`);
});

test("initial schema and migration use the same GitHub schema", () => {
  const migrationBlock = migration.slice(migration.indexOf("CREATE TABLE"), migration.lastIndexOf("COMMIT;")).trim();
  assert.equal(migrationBlock, initBlock.trim());
  sql(initBlock);
  assert.equal(scalar(`SELECT is_identity FROM information_schema.columns WHERE table_schema='core'
    AND table_name='github_projects' AND column_name='id'`), "YES");
});
test("migration is repeatable without touching existing projects or unrelated data", () => {
  sql("CREATE TABLE core.existing_data (content TEXT); INSERT INTO core.existing_data VALUES ('keep');");
  sql(migration);
  assert.equal(scalar("INSERT INTO core.github_projects (category, full_name, repository_url, notes) VALUES ('PERSONAL_DEMO', 'owner/demo', 'https://github.com/owner/demo', '**notes**') RETURNING id"), "1");
  sql(migration);
  assert.equal(scalar("SELECT notes || ':' || sync_status FROM core.github_projects"), "**notes**:PENDING");
  assert.equal(scalar("SELECT content FROM core.existing_data"), "keep");
  assert.equal(scalar("INSERT INTO core.github_projects (category, full_name, repository_url) VALUES ('LEARNING', 'owner/second', 'https://github.com/owner/second') RETURNING id"), "2");
});
test("category-scoped uniqueness handles case and repository renames while allowing both categories", () => {
  sql(migration);
  sql(`INSERT INTO core.github_projects (category, full_name, repository_url, github_repo_id) VALUES
    ('PERSONAL_DEMO', 'Owner/Demo', 'https://github.com/owner/demo', 123),
    ('LEARNING', 'Owner/Demo', 'https://github.com/owner/demo', 123);`);
  assert.equal(scalar("SELECT count(*) FROM core.github_projects"), "2");
  assert.notEqual(sql("INSERT INTO core.github_projects (category, full_name, repository_url) VALUES ('PERSONAL_DEMO', 'owner/demo', 'https://github.com/owner/demo');", true).status, 0);
  assert.notEqual(sql("INSERT INTO core.github_projects (category, full_name, repository_url, github_repo_id) VALUES ('LEARNING', 'Owner/Renamed', 'https://github.com/owner/renamed', 123);", true).status, 0);
  assert.notEqual(sql("UPDATE core.github_projects SET category = 'PERSONAL_DEMO' WHERE category = 'LEARNING';", true).status, 0);
});
test("database enforces category, status and counters and supports topic arrays", () => {
  sql(migration);
  sql("INSERT INTO core.github_projects (category, full_name, repository_url, topics) VALUES ('LEARNING', 'owner/demo', 'https://github.com/owner/demo', ARRAY['agent','a,b']);");
  assert.equal(scalar("SELECT topics[2] FROM core.github_projects"), "a,b");
  for (const assignment of ["category = 'OTHER'", "category = NULL", "sync_status = 'OTHER'", "stars = -1", "forks = -1"]) {
    assert.notEqual(sql(`UPDATE core.github_projects SET ${assignment};`, true).status, 0);
  }
});
