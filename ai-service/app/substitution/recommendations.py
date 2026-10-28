from app.schemas.substitutions import (
    RankedCandidate,
    SubstitutionRequest,
    SubstitutionResponse,
)


WEIGHTS = {
    "qualification": 40,
    "department": 20,
    "section_familiarity": 15,
    "remaining_capacity": 15,
    "experience": 10,
}


def recommend_substitutes(request: SubstitutionRequest) -> SubstitutionResponse:
    ranked: list[RankedCandidate] = []
    excluded: list[str] = []
    for member in request.candidates:
        if not member.qualified or not member.available or member.remaining_periods < request.required_periods:
            excluded.append(member.id)
            continue
        capacity = min(100, round(100 * member.remaining_periods / member.max_weekly_periods))
        dept = 100 if member.department_id == request.department_id else 0
        score = round(
            40 * 100
            + 20 * dept
            + 15 * member.section_familiarity
            + 15 * capacity
            + 10 * member.experience_score,
            2,
        ) / 100
        reasons = ["Qualified for the subject", "Available for the affected period"]
        if dept:
            reasons.append("Belongs to the same department")
        if member.section_familiarity:
            reasons.append(f"Section familiarity: {member.section_familiarity}%")
        reasons.append(f"Remaining weekly workload capacity: {member.remaining_periods} periods")
        ranked.append(RankedCandidate(
            id=member.id,
            name=member.name,
            score=score,
            reasons=reasons,
            department_name=member.department_name,
            department_code=member.department_code,
            department_block_no=member.department_block_no,
        ))
    ranked.sort(key=lambda item: (-item.score, item.name.casefold(), item.id))
    return SubstitutionResponse(candidates=ranked, excluded_candidate_ids=excluded, weights=WEIGHTS)
