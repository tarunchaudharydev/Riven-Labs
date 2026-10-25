from fastapi import APIRouter, Depends, HTTPException

from app.core.config import settings
from app.core.security import require_backend_key
from app.schemas.requirements import ParseRequirementRequest, RequirementInterpretation
from app.services.requirement_service import RequirementService
from app.ai.providers.gemini_provider import GeminiProvider

router = APIRouter(prefix="/requirements", tags=["Requirements"], dependencies=[Depends(require_backend_key)])


@router.post("/parse", response_model=RequirementInterpretation)
async def parse_requirement(request: ParseRequirementRequest) -> RequirementInterpretation:
    if not settings.gemini_api_key:
        raise HTTPException(status_code=503, detail="Requirement interpretation is not configured. Set GEMINI_API_KEY.")
    service = RequirementService(GeminiProvider(settings.gemini_api_key))
    try:
        return await service.parse(request)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
