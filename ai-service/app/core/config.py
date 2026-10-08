# from pydantic_settings import BaseSettings


# class Settings(BaseSettings):
#     openai_api_key: str

#     class Config:
#         env_file = ".env"


# settings = Settings().



# gemini
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    gemini_api_key: str

    class Config:
        env_file = ".env"


settings = Settings()