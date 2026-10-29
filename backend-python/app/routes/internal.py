"""Internal endpoints for Markdown parsing and document indexing."""

from uuid import UUID

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.indexing import parse_markdown, process_document_job

router = APIRouter(prefix="/internal/v1")


class MarkdownParseRequest(BaseModel):
    """Markdown content to normalize and split into chunks."""

    markdown: str


class DocumentIndexRequest(BaseModel):
    """An existing document indexing job to run."""

    jobId: UUID


@router.post("/parse/markdown")
def parse_markdown_endpoint(request: MarkdownParseRequest) -> dict[str, object]:
    """Preview normalized text and chunk metadata without writing data."""
    parsed = parse_markdown(request.markdown)
    return {
        "normalizedText": parsed.normalized_text,
        "chunks": [
            {
                "index": chunk.index,
                "titlePath": chunk.title_path,
                "content": chunk.content,
                "tokenCount": chunk.token_count,
                "metadata": chunk.metadata,
            }
            for chunk in parsed.chunks
        ],
    }


@router.post("/index/documents")
def index_documents(request: DocumentIndexRequest) -> dict[str, object]:
    """Run an existing indexing job; the worker normally handles this."""
    try:
        return process_document_job(request.jobId)
    except Exception as exception:
        raise HTTPException(
            status_code=500,
            detail={"code": "DOCUMENT_INDEX_FAILED", "message": str(exception)},
        ) from exception
