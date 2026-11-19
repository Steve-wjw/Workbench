"""FastAPI application entry point for the Python service."""

from datetime import datetime, timezone

from fastapi import FastAPI

from app.routes.internal import router as internal_router

app = FastAPI(
    title="Personal AI Workbench RAG Service",
    version="1.0.0",
)
app.include_router(internal_router)


@app.get("/internal/v1/health")
def health() -> dict[str, str]:
    """Return the liveness status consumed by the Java API."""
    return {
        "service": "python-rag",
        "status": "UP",
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


@app.get("/health")
def public_health() -> dict[str, str]:
    """Expose the same health payload for local manual checks."""
    return health()
