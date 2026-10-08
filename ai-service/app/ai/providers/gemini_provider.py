# from openai import AsyncOpenAI

# from app.schemas.requirements import TimetableRequirement


# class OpenAIProvider:

#     def __init__(self, api_key: str):
#         self.client = AsyncOpenAI(api_key=api_key)

#     async def parse_timetable_requirement(
#         self,
#         user_input: str,
#     ) -> TimetableRequirement:

#         response = await self.client.responses.parse(
#             model="gpt-5.5",
#             input=[
#                 {
#                     "role": "system",
#                     "content": (
#                         "You extract timetable requirements from natural "
#                         "language. Extract only information explicitly "
#                         "provided by the user. Never invent teachers, "
#                         "subjects, class counts, or constraints."
#                     ),
#                 },
#                 {
#                     "role": "user",
#                     "content": user_input,
#                 },
#             ],
#             text_format=TimetableRequirement,
#         )

#         return response.output_parsed



# gemini
from google import genai

from app.core.config import settings
from app.schemas.requirements import TimetableRequirement


class GeminiProvider:
    def __init__(self, api_key: str):
        self.client = genai.Client(api_key=api_key)

    async def parse_timetable_requirement(
        self,
        user_input: str,
    ) -> TimetableRequirement:

        prompt = f"""
You extract timetable requirements from natural language.

Rules:
- Extract only information explicitly provided by the user.
- Never invent teachers, subjects, class counts, or constraints.
- Every constraint that clearly refers to a particular subject must include
  that subject in the "subject" field.
- If a constraint applies to the entire section, set "subject" to null.
- Preserve the meaning of the user's requirement.
- Do not make assumptions.

Examples:

"Mathematics should preferably be in the morning."
→ type = PREFERRED_TIME
→ subject = Mathematics
→ value = morning

"Mathematics should not occur more than once per day."
→ type = MAX_CLASSES_PER_DAY
→ subject = Mathematics
→ value = 1

"No classes on Saturday."
→ type = AVOID_DAY
→ subject = null
→ value = Saturday

User requirement:
{user_input}
"""

        response = await self.client.aio.models.generate_content(
            model="gemini-3.5-flash-lite",
            contents=prompt,
            config={
                "response_mime_type": "application/json",
                "response_schema": TimetableRequirement,
            },
        )

        return response.parsed