"""Manual Gemini configuration check; does not run an API call during test discovery."""

if __name__ == "__main__":
    from google import genai
    from app.core.config import settings

    if not settings.gemini_api_key:
        raise SystemExit("Set GEMINI_API_KEY before listing remote Gemini models.")
    for model in genai.Client(api_key=settings.gemini_api_key).models.list():
        print(model.name)
