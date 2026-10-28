from app.schemas.requirements import (
    ParseRequirementRequest,
    RequirementInterpretation,
    validate_catalog_references,
)


class RequirementService:
    def __init__(self, provider):
        self.provider = provider

    async def parse(self, request: ParseRequirementRequest) -> RequirementInterpretation:
        request_text = request.text
        if request.conversation:
            transcript = "\n".join(
                f"{turn.role.upper()}: {turn.content.strip()}" for turn in request.conversation
            )
            request_text = (
                "Conversation history (the latest USER message below is the change to interpret):\n"
                f"{transcript}\n\nLATEST USER MESSAGE:\n{request.text}"
            )
        parsed = await self.provider.parse_timetable_requirement(request_text, request.catalog)
        parsed = parsed.model_copy(update={"original_text": request.text})
        return validate_catalog_references(parsed, request.catalog)
