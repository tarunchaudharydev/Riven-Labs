# # from app.ai.providers.openai_provider import OpenAIProvider
# # gemini
# from app.ai.providers.gemini_provider import GeminiProvider
# from app.schemas.requirements import TimetableRequirement


# class RequirementService:

#     def __init__(self, provider: OpenAIProvider):
#         self.provider = provider

#     async def parse(
#         self,
#         user_input: str,
#     ) -> TimetableRequirement:

#         return await self.provider.parse_timetable_requirement(
#             user_input
#         )

# gemini 
from app.ai.providers.gemini_provider import GeminiProvider
from app.schemas.requirements import TimetableRequirement


class RequirementService:
    def __init__(self, provider: GeminiProvider):
        self.provider = provider

    async def parse(self, user_input: str) -> TimetableRequirement:
        return await self.provider.parse_timetable_requirement(user_input)