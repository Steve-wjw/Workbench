"""Configuration model for local Python services."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration loaded from environment variables and `.env`."""

    # Database and local storage settings.
    database_url: str = ""

    # Offline hash embeddings, not an external model or network service.
    embedding_model: str = "BAAI/bge-m3"
    embedding_dimension: int = 1024

    # Keep the existing environment key for local chunk sizing.
    rag_chunk_max_tokens: int = 420

    # Keep local development configuration in the project root without
    # requiring secrets to be committed to version control.
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
