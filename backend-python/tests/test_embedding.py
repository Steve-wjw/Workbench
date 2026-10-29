"""Tests for the deterministic local embedding helpers."""

from app.embedding import embed_text, tokenize, vector_literal


def test_tokenize_supports_mixed_chinese_and_english() -> None:
    """Chinese characters and English terms should remain searchable."""
    assert tokenize("Transformer 的位置编码 position") == [
        "transformer",
        "的",
        "位",
        "置",
        "编",
        "码",
        "position",
    ]


def test_embedding_is_deterministic_and_normalized() -> None:
    """The same local input must produce an identical unit vector."""
    first = embed_text("Transformer 位置编码")
    second = embed_text("Transformer 位置编码")

    assert first == second
    assert round(sum(value * value for value in first), 6) == 1.0


def test_empty_embedding_and_vector_literal() -> None:
    """Empty content stays finite and vectors use pgvector text format."""
    assert all(value == 0 for value in embed_text(""))
    assert vector_literal([0.5, -0.5]) == "[0.500000,-0.500000]"
