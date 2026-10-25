from app.schemas.requirements import RequirementCatalog, RequirementInterpretation


class MockProvider:
    """Test-only provider; production routes construct GeminiProvider explicitly."""

    def __init__(self, result: RequirementInterpretation):
        self.result = result

    async def parse_timetable_requirement(self, user_input: str, catalog: RequirementCatalog) -> RequirementInterpretation:
        return self.result.model_copy(update={"original_text": user_input})
