"""Regression tests for retained parsing, routes and archive protection."""

from unittest.mock import Mock
from uuid import uuid4

from fastapi.testclient import TestClient

from app.indexing import fail_job, finish_job, mark_job_running, parse_markdown
from app.main import app


def test_parse_markdown_preserves_code_and_headings() -> None:
    """Markdown parsing keeps useful chunk metadata without an LLM."""
    parsed = parse_markdown("# Topic\n\n- item\n\n```python\nprint('ok')\n```")
    assert "Topic" in parsed.normalized_text
    assert any("print('ok')" in chunk.content for chunk in parsed.chunks)
    assert any("Topic" in chunk.title_path for chunk in parsed.chunks)
    assert all(chunk.token_count > 0 for chunk in parsed.chunks)


def test_internal_parse_endpoint_accepts_json_body() -> None:
    """The retained API accepts the body shape used by local callers."""
    with TestClient(app) as client:
        response = client.post("/internal/v1/parse/markdown", json={"markdown": "# Topic\n\nbody"})
    assert response.status_code == 200
    assert response.json()["chunks"]


def test_unused_ai_routes_are_not_registered() -> None:
    """Removed query and extraction workflows must not leave live routes."""
    paths = set(app.openapi()["paths"])
    assert "/internal/v1/parse/markdown" in paths
    assert "/internal/v1/index/documents" in paths
    assert not any(part in path for path in paths for part in ("generation", "retrieval", "providers"))


def test_worker_updates_preserve_archive_state() -> None:
    """Every worker state update retains an archive chosen by the user."""
    connection = Mock()
    job_id, document_id = uuid4(), uuid4()
    mark_job_running(connection, job_id)
    finish_job(connection, job_id, document_id, parse_markdown("body"))
    fail_job(connection, job_id, document_id, "test failure")
    updates = [
        call.args[0]
        for call in connection.execute.call_args_list
        if "UPDATE core.documents" in call.args[0]
    ]
    assert len(updates) == 3
    assert all("THEN 'ARCHIVED'" in sql for sql in updates)
