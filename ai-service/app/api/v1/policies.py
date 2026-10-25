from fastapi import APIRouter, Depends

from app.core.security import require_backend_key
from app.rag.policy_service import answer_policy_question
from app.schemas.policies import PolicyAnswer, PolicyQueryRequest

router = APIRouter(prefix="/policies", tags=["Policies"], dependencies=[Depends(require_backend_key)])


@router.post("/answer", response_model=PolicyAnswer)
async def answer(request: PolicyQueryRequest) -> PolicyAnswer:
    return await answer_policy_question(request)
