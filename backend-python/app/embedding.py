"""Deterministic offline hash vectors for document indexing."""

from __future__ import annotations

import hashlib
import math
import re

from app.config import settings

TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]")


def tokenize(text: str) -> list[str]:
    """Tokenize mixed English and Chinese text for local retrieval."""
    return [token.lower() for token in TOKEN_PATTERN.findall(text or "")]


def embed_text(text: str) -> list[float]:
    """Create a normalized local hash embedding with the configured dimension."""
    dimension = settings.embedding_dimension
    vector = [0.0] * dimension
    for token in tokenize(text):
        digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
        bucket = int.from_bytes(digest[:4], "big") % dimension
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        vector[bucket] += sign
    length = math.sqrt(sum(value * value for value in vector))
    if length == 0:
        return vector
    return [value / length for value in vector]


def vector_literal(vector: list[float]) -> str:
    """Convert a Python vector to the text format accepted by pgvector."""
    return "[" + ",".join(f"{value:.6f}" for value in vector) + "]"
