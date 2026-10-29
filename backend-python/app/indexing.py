"""Markdown parsing and document indexing for the local RAG loop.

The worker normalizes Markdown, builds retrieval-sized chunks and persists
offline local embeddings in pgvector-compatible format.
"""

from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import psycopg
from psycopg.rows import dict_row

from app.config import settings
from app.embedding import embed_text, vector_literal


@dataclass(frozen=True)
class MarkdownChunk:
    """A normalized Markdown chunk ready to be persisted."""

    index: int
    title_path: str
    content: str
    token_count: int
    metadata: dict[str, Any]


@dataclass(frozen=True)
class ParsedMarkdown:
    """Parsed Markdown result used by both API and worker flows."""

    normalized_text: str
    chunks: list[MarkdownChunk]


def parse_markdown(markdown: str) -> ParsedMarkdown:
    """Parse Markdown into normalized text and coarse semantic chunks."""
    lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    title_stack: list[str] = []
    blocks: list[dict[str, Any]] = []
    current: list[str] = []
    current_type = "paragraph"
    current_lang = ""
    in_code = False
    code_lang = ""

    def flush() -> None:
        nonlocal current, current_type, current_lang
        text = "\n".join(line.rstrip() for line in current).strip()
        if not text:
            current = []
            current_type = "paragraph"
            current_lang = ""
            return
        blocks.append(
            {
                "type": current_type,
                "title_path": " / ".join(title_stack),
                "content": text,
                "language": current_lang,
            }
        )
        current = []
        current_type = "paragraph"
        current_lang = ""

    for raw_line in lines:
        line = raw_line.rstrip()
        fence = re.match(r"^```([\w+-]*)\s*$", line)
        if fence:
            if in_code:
                flush()
                in_code = False
                code_lang = ""
            else:
                flush()
                in_code = True
                code_lang = fence.group(1) or ""
                current_type = "code"
                current_lang = code_lang
            continue
        if in_code:
            current.append(raw_line)
            continue

        heading = re.match(r"^(#{1,6})\s+(.+)$", line)
        if heading:
            flush()
            level = len(heading.group(1))
            title = heading.group(2).strip()
            title_stack = title_stack[: level - 1] + [title]
            blocks.append(
                {
                    "type": "heading",
                    "title_path": " / ".join(title_stack),
                    "content": title,
                    "level": level,
                }
            )
            continue

        if re.match(r"^\s*([-*+]|\d+\.)\s+", line):
            if current_type not in {"list", "paragraph"}:
                flush()
            current_type = "list"
            current.append(re.sub(r"^\s*([-*+]|\d+\.)\s+", "- ", line))
            continue

        if not line.strip():
            flush()
            continue

        if current_type != "paragraph":
            flush()
        current_type = "paragraph"
        current.append(line.strip())

    flush()
    normalized_parts = [block["content"] for block in blocks if block["content"].strip()]
    normalized_text = "\n\n".join(normalized_parts)
    chunks = build_chunks(blocks)
    return ParsedMarkdown(normalized_text=normalized_text, chunks=chunks)


def process_document_job(job_id: uuid.UUID) -> dict[str, Any]:
    """Process one document indexing job and persist chunk metadata."""
    with psycopg.connect(settings.database_url, row_factory=dict_row) as connection:
        with connection.transaction():
            job = connection.execute(
                """
                SELECT j.id, j.resource_id, j.force_rebuild, d.content
                FROM core.index_jobs j
                JOIN core.documents d ON d.id = j.resource_id
                WHERE j.id = %s AND j.job_type = 'INDEX_DOCUMENT'
                FOR UPDATE OF j
                """,
                (job_id,),
            ).fetchone()
            if job is None:
                raise ValueError(f"index job not found: {job_id}")
            mark_job_running(connection, job_id)

        try:
            parsed = parse_markdown(job["content"])
            with connection.transaction():
                persist_chunks(connection, job["resource_id"], job_id, parsed)
                finish_job(connection, job_id, job["resource_id"], parsed)
            return {
                "jobId": str(job_id),
                "documentId": str(job["resource_id"]),
                "status": "SUCCEEDED",
                "chunkCount": len(parsed.chunks),
            }
        except Exception as exception:
            with connection.transaction():
                fail_job(connection, job_id, job["resource_id"], str(exception))
            raise


def claim_next_document_job() -> uuid.UUID | None:
    """Claim the next pending document indexing job."""
    with psycopg.connect(settings.database_url, row_factory=dict_row) as connection:
        with connection.transaction():
            row = connection.execute(
                """
                SELECT id
                FROM core.index_jobs
                WHERE job_type = 'INDEX_DOCUMENT'
                  AND status IN ('PENDING', 'RETRY_WAITING')
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """
            ).fetchone()
            if row is None:
                return None
            job_id = row["id"]
            mark_job_running(connection, job_id)
            return job_id


def mark_job_running(connection: psycopg.Connection[Any], job_id: uuid.UUID) -> None:
    """Mark a job and its document as running."""
    connection.execute(
        """
        UPDATE core.index_jobs
        SET status = 'RUNNING',
            attempt_count = CASE WHEN status = 'RUNNING' THEN attempt_count ELSE attempt_count + 1 END,
            started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP
        WHERE id = %s
        """,
        (job_id,),
    )
    connection.execute(
        """
        UPDATE core.documents d
        SET status = CASE WHEN d.status = 'ARCHIVED' THEN 'ARCHIVED' ELSE 'INDEXING' END,
            index_error = NULL,
            updated_at = CURRENT_TIMESTAMP
        FROM core.index_jobs j
        WHERE j.id = %s AND d.id = j.resource_id
        """,
        (job_id,),
    )


def persist_chunks(
    connection: psycopg.Connection[Any],
    document_id: uuid.UUID,
    job_id: uuid.UUID,
    parsed: ParsedMarkdown,
) -> None:
    """Replace existing chunk metadata for one document."""
    connection.execute("DELETE FROM rag.document_chunks WHERE document_id = %s", (document_id,))
    for chunk in parsed.chunks:
        embedding = vector_literal(embed_text(f"{chunk.title_path}\n{chunk.content}"))
        connection.execute(
            """
            INSERT INTO rag.document_chunks
                (id, document_id, job_id, chunk_index, title_path, content, token_count,
                 embedding, embedding_model, metadata)
            VALUES
                (%s, %s, %s, %s, %s, %s, %s, %s::vector, %s, %s::jsonb)
            """,
            (
                uuid.uuid4(),
                document_id,
                job_id,
                chunk.index,
                chunk.title_path,
                chunk.content,
                chunk.token_count,
                embedding,
                settings.embedding_model,
                json.dumps(chunk.metadata, ensure_ascii=False),
            ),
        )


def finish_job(
    connection: psycopg.Connection[Any],
    job_id: uuid.UUID,
    document_id: uuid.UUID,
    parsed: ParsedMarkdown,
) -> None:
    """Mark a document indexing job as succeeded."""
    metadata = {
        "chunkCount": len(parsed.chunks),
        "indexedAt": datetime.now(UTC).isoformat(),
    }
    connection.execute(
        """
        UPDATE core.documents
        SET normalized_content = %s,
            status = CASE WHEN status = 'ARCHIVED' THEN 'ARCHIVED' ELSE 'INDEXED' END,
            index_error = NULL,
            indexed_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = %s
        """,
        (parsed.normalized_text, document_id),
    )
    connection.execute(
        """
        UPDATE core.index_jobs
        SET status = 'SUCCEEDED',
            error_message = NULL,
            metadata = %s::jsonb,
            finished_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = %s
        """,
        (json.dumps(metadata, ensure_ascii=False), job_id),
    )


def fail_job(
    connection: psycopg.Connection[Any],
    job_id: uuid.UUID,
    document_id: uuid.UUID,
    message: str,
) -> None:
    """Record a document indexing failure and retry until the attempt limit."""
    error = message[:2000]
    connection.execute(
        """
        UPDATE core.documents
        SET status = CASE
                WHEN status = 'ARCHIVED' THEN 'ARCHIVED'
                WHEN (
                    SELECT attempt_count < max_attempts
                    FROM core.index_jobs
                    WHERE id = %s
                ) THEN 'PENDING_INDEX'
                ELSE 'FAILED'
            END,
            index_error = %s,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = %s
        """,
        (job_id, error, document_id),
    )
    connection.execute(
        """
        UPDATE core.index_jobs
        SET status = CASE WHEN attempt_count < max_attempts THEN 'RETRY_WAITING' ELSE 'FAILED' END,
            error_message = %s,
            finished_at = CASE WHEN attempt_count < max_attempts THEN NULL ELSE CURRENT_TIMESTAMP END,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = %s
        """,
        (error, job_id),
    )


def estimate_token_count(text: str) -> int:
    """Estimate token count without loading an embedding tokenizer."""
    english_words = len(re.findall(r"[A-Za-z0-9_]+", text))
    cjk_chars = len(re.findall(r"[\u4e00-\u9fff]", text))
    punctuation = max(1, len(text) // 24)
    return max(1, english_words + cjk_chars + punctuation)


def build_chunks(blocks: list[dict[str, Any]]) -> list[MarkdownChunk]:
    """Merge parsed Markdown blocks into retrieval-sized chunks."""
    chunks: list[MarkdownChunk] = []
    current_parts: list[str] = []
    current_types: list[str] = []
    current_title_path = ""
    current_tokens = 0
    max_tokens = max(80, settings.rag_chunk_max_tokens)

    def flush() -> None:
        nonlocal current_parts, current_types, current_title_path, current_tokens
        content = "\n\n".join(current_parts).strip()
        if not content:
            return
        chunks.append(
            MarkdownChunk(
                index=len(chunks),
                title_path=current_title_path,
                content=content,
                token_count=estimate_token_count(content),
                metadata={"blockTypes": current_types},
            )
        )
        current_parts = []
        current_types = []
        current_title_path = ""
        current_tokens = 0

    for block in blocks:
        content = block.get("content", "").strip()
        if not content:
            continue
        block_type = block.get("type", "paragraph")
        title_path = block.get("title_path", "")
        token_count = estimate_token_count(content)
        if block_type == "code":
            flush()
            chunks.append(
                MarkdownChunk(
                    index=len(chunks),
                    title_path=title_path,
                    content=content,
                    token_count=token_count,
                    metadata={key: value for key, value in block.items() if key not in {"title_path", "content"}},
                )
            )
            continue
        title_changed = bool(current_title_path and title_path != current_title_path)
        would_overflow = current_tokens > 0 and current_tokens + token_count > max_tokens
        if title_changed or would_overflow:
            flush()
        current_title_path = title_path
        current_parts.append(content)
        current_types.append(block_type)
        current_tokens += token_count

    flush()
    return chunks
