from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Candidate(StrictModel):
    id: str
    name: str
    department_id: str
    qualified: bool
    available: bool
    remaining_periods: int = Field(ge=0)
    max_weekly_periods: int = Field(ge=1)
    section_familiarity: int = Field(ge=0, le=100)
    experience_score: int = Field(ge=0, le=100)


class SubstitutionRequest(StrictModel):
    subject_id: str
    department_id: str
    required_periods: int = Field(default=1, ge=1, le=6)
    candidates: list[Candidate] = Field(max_length=1000)


class RankedCandidate(StrictModel):
    id: str
    name: str
    score: float
    reasons: list[str]


class SubstitutionResponse(StrictModel):
    candidates: list[RankedCandidate]
    excluded_candidate_ids: list[str]
    weights: dict[str, int]
