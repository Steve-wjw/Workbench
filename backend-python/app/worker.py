"""Local worker process for background indexing jobs."""

import logging
import time
from pathlib import Path

import psycopg

from app.config import settings
from app.indexing import (
    claim_next_document_job,
    process_document_job,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("workbench-worker")


def wait_for_database() -> None:
    """Block until PostgreSQL accepts a connection."""
    while True:
        try:
            with psycopg.connect(settings.database_url) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT 1")
            logger.info("database connection is ready")
            return
        except Exception as exception:
            logger.warning("database is not ready: %s", exception)
            time.sleep(2)


def run() -> None:
    """Initialize the worker and poll for pending indexing jobs."""
    wait_for_database()
    Path("/tmp/worker-ready").touch()
    logger.info("worker initialized; polling document index jobs")
    while True:
        try:
            job_id = claim_next_document_job()
            if job_id is not None:
                logger.info("processing document index job %s", job_id)
                result = process_document_job(job_id)
                logger.info("document index job completed: %s", result)
                continue
            time.sleep(2)
        except Exception as exception:
            logger.exception("document index job failed: %s", exception)
            time.sleep(2)


if __name__ == "__main__":
    run()
