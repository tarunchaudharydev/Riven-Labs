import json

from google import genai

from app.core.config import settings
from app.schemas.requirements import RequirementCatalog, RequirementInterpretation


GEMINI_REQUIREMENT_SCHEMA = {
    "type": "object",
    "properties": {
        "original_text": {"type": "string"},
        "changes": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "subject_id": {"type": "string"},
                    "section_id": {"type": "string"},
                    "sessions_per_week": {"type": ["integer", "null"]},
                    "preferred_faculty_ids": {"type": "array", "items": {"type": "string"}},
                    "excluded_slot_ids": {"type": "array", "items": {"type": "string"}},
                    "preferred_slot_ids": {"type": "array", "items": {"type": "string"}},
                    "periods_per_session": {"type": ["integer", "null"]},
                    "priority": {"type": "string", "enum": ["HARD", "SOFT"]},
                },
                "required": [
                    "subject_id",
                    "section_id",
                    "sessions_per_week",
                    "preferred_faculty_ids",
                    "excluded_slot_ids",
                    "preferred_slot_ids",
                    "periods_per_session",
                    "priority",
                ],
                "additionalProperties": False,
            },
        },
        "clarification_questions": {"type": "array", "items": {"type": "string"}},
        "unsupported_requirements": {"type": "array", "items": {"type": "string"}},
        "requires_review": {"type": "boolean"},
    },
    "required": [
        "original_text",
        "changes",
        "clarification_questions",
        "unsupported_requirements",
        "requires_review",
    ],
    "additionalProperties": False,
}


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
                # Use a compact schema with only Gemini's supported JSON Schema
                # keywords. Pydantic applies the stricter app-side validation below.
                "response_json_schema": GEMINI_REQUIREMENT_SCHEMA,
            },
        )
        if not response.text:
            raise ValueError("Gemini returned an empty requirement interpretation")
        return RequirementInterpretation.model_validate_json(response.text)
