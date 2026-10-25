from fastapi import APIRouter, Depends

from app.core.security import require_backend_key
from app.scheduling.solver import solve_timetable
from app.schemas.scheduling import ScheduleResult, SchedulingProblem

router = APIRouter(prefix="/timetables", tags=["Timetables"], dependencies=[Depends(require_backend_key)])


@router.post("/solve", response_model=ScheduleResult)
def solve(problem: SchedulingProblem) -> ScheduleResult:
    return solve_timetable(problem)
