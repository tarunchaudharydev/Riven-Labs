from app.schemas.requirements import (
    ParseRequirementRequest,
    RequirementInterpretation,
    validate_catalog_references,
)


class RequirementService:
    def __init__(self, provider):
        self.provider = provider

    async def parse(self, request: ParseRequirementRequest) -> RequirementInterpretation:
        parsed = await self.provider.parse_timetable_requirement(request.text, request.catalog)
        parsed = parsed.model_copy(update={"original_text": request.text})
        return validate_catalog_references(parsed, request.catalog)
