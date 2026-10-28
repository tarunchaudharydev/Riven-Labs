import json

from google import genai

from app.core.config import settings
from app.schemas.requirements import RequirementCatalog, RequirementInterpretation


class GeminiProvider:
    def __init__(self, api_key: str | None = None):
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not configured")
        self.client = genai.Client(api_key=api_key)
        self.model = settings.gemini_model

    async def parse_timetable_requirement(self, user_input: str, catalog: RequirementCatalog) -> RequirementInterpretation:
        prompt = """Interpret scheduling language into the requested JSON schema. Set original_text to the user's request verbatim. Use only IDs from the supplied catalog.
Never create or modify a timetable. Do not guess an ambiguous entity: add a clarification question instead.
Only extract explicit requirements. Treat stated prohibitions as excluded_slot_ids; preferences as preferred IDs.
Use HARD only for explicit mandatory language. Keep requires_review true. If a request is unsupported, explain it in unsupported_requirements.
Return an empty changes list when the request cannot be mapped safely.

Catalog:
""" + json.dumps(catalog.model_dump(), ensure_ascii=False)
        response = await self.client.aio.models.generate_content(
            model=self.model,
            contents=f"{prompt}\n\nUser request:\n{user_input}",
            config={
                "response_mime_type": "application/json",
                # Pass JSON Schema directly; the typed response_schema path
                # serializes additionalProperties as unsupported snake_case.
                "response_json_schema": RequirementInterpretation.model_json_schema(),
            },
        )
        if not response.text:
            raise ValueError("Gemini returned an empty requirement interpretation")
        return RequirementInterpretation.model_validate_json(response.text)
