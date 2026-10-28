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
                    "max_sessions_per_week": {"type": ["integer", "null"]},
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
                    "max_sessions_per_week",
                    "preferred_faculty_ids",
                    "excluded_slot_ids",
                    "preferred_slot_ids",
                    "periods_per_session",
                    "priority",
                ],
                "additionalProperties": False,
            },
        },
        "faculty_unavailability": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "faculty_id": {"type": "string"},
                    "excluded_slot_ids": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["faculty_id", "excluded_slot_ids"],
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
        "faculty_unavailability",
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
        prompt = """You are a careful, conversational university timetable planning assistant. Understand informal, typo-tolerant requests and the conversation context, then return the requested JSON. Do not generate a timetable; a separate constraint solver does that after a person reviews your interpretation.

Interpret the whole request, not only exact phrases. Resolve common abbreviations, spelling mistakes, weekday names, AM/PM times, ranges, and names against the supplied catalog. Use only catalog IDs. If a name or abbreviation matches more than one catalog item, ask one concise clarification question instead of guessing. Preserve the user's original wording verbatim in original_text. The catalog may include two shift windows, each section's fixed shift_id, and each time slot's shift_ids. Never move a section between shifts through a scheduling request. Consider only slots that belong to that section's configured shift. If a requested time conflicts with its shift, describe the conflict in unsupported_requirements or ask for clarification.

When conversation history is present, use earlier user messages to resolve follow-up references such as "that section", "same teacher", "make it three times", or "avoid the other slot". Treat earlier assistant messages as context only, never as an approved schedule or a source of catalog facts. Interpret the latest user message as a refinement of the conversation, and return the complete set of still-applicable constraints from the conversation so far. If the follow-up conflicts with an earlier instruction, prefer the latest instruction and ask a concise clarification only when the intended schedule is still ambiguous. Do not invent missing catalog items or silently discard a prior constraint.

Supported timetable changes:
- Match each subject and section to one unique catalog item. A request may mention multiple subject-section pairs; return one change per pair.
- Set sessions_per_week for an exact weekly count ("schedule three times a week"). Set max_sessions_per_week for a stated cap ("at most three", "no more than three", "maximum three"). Never convert a cap into an exact count. A cap may reduce the approved weekly count but must not increase it. Set periods_per_session only for an explicit block length.
- For a time or day the user wants to avoid, put the matching slot IDs in excluded_slot_ids. For preferred times or days, use preferred_slot_ids. Treat phrases like "only between 10 and 12", "must finish before noon", or "not after 3" as hard limits: exclude slots outside the allowed range. Treat "prefer", "ideally", or "if possible" as soft preferences.
- Match human time expressions using each slot's day, period, label, start_time, and end_time. Include a slot in a window only when its actual time is supported by those fields; never guess clock times from period numbers alone. If the catalog has no usable clock times for a requested clock-time constraint, explain that in unsupported_requirements.
- If a user says a day is off and that day has no catalog slots, consider that constraint already satisfied; do not call it unsupported. If that day has catalog slots, exclude all of them.
- For a named faculty member's recurring weekly leave/unavailability, put their catalog ID and the affected slot IDs in faculty_unavailability. This applies only to recurring weekly patterns. Do not turn a one-off date (for example, "the first Wednesday") into a weekly restriction; ask whether it should apply every week or only on that date. If they say a faculty member is preferred, use preferred_faculty_ids instead.
- A constraint that the schema or solver cannot represent must be explained in unsupported_requirements. Never silently turn a hard rule into a preference or invent an ID.

Set priority to HARD only for explicit mandatory language; otherwise use SOFT. Keep requires_review true. The output must be complete and safe to apply: if an ambiguity affects what would be scheduled, include a clarification question. Leave unrelated fields empty or null rather than inventing values.

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
