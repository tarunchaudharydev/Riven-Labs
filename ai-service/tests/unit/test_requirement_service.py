import asyncio

from app.ai.providers.mock_provider import MockProvider
from app.services.requirement_service import RequirementService


def test_requirement_service():
    async def run_test():
        provider = MockProvider()
        service = RequirementService(provider)

        result = await service.parse(
            "Mathematics 4 times per week"
        )

        assert result.section == "CSE A"
        assert result.subjects[0].subject == "Mathematics"
        assert result.subjects[0].classes_per_week == 4

    asyncio.run(run_test())