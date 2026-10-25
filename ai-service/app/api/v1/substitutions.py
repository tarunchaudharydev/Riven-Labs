from fastapi import APIRouter, Depends

from app.core.security import require_backend_key
from app.schemas.substitutions import SubstitutionRequest, SubstitutionResponse
from app.substitution.recommendations import recommend_substitutes

router = APIRouter(prefix="/substitutions", tags=["Substitutions"], dependencies=[Depends(require_backend_key)])


@router.post("/recommend", response_model=SubstitutionResponse)
def recommend(request: SubstitutionRequest) -> SubstitutionResponse:
    return recommend_substitutes(request)
