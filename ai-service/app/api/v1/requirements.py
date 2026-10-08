from fastapi import APIRouter
from pydantic import BaseModel

from app.ai.providers.gemini_provider import GeminiProvider
from app.core.config import settings
from app.schemas.requirements import TimetableRequirement
from app.services.requirement_service import RequirementService


router = APIRouter(
    prefix="/requirements",
    tags=["Requirements"],
)


provider = GeminiProvider(
    api_key=settings.gemini_api_key
)

service = RequirementService(provider)


class RequirementRequest(BaseModel):
    text: str


@router.post("/parse", response_model=TimetableRequirement)
async def parse_requirement(
    request: RequirementRequest,
):
    return await service.parse(request.text)