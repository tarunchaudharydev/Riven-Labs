from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    gemini_api_key: str | None = None
    gemini_model: str = "gemini-3.8-flash"
    ai_service_api_key: str | None = None
    cors_origins: str = "http://localhost:3000"
    solver_time_limit_seconds: float = 15.0


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
