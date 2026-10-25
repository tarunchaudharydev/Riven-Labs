from app.schemas.substitutions import Candidate, SubstitutionRequest
from app.substitution.recommendations import recommend_substitutes


def test_unavailable_or_unqualified_candidates_are_excluded_before_scoring():
    result = recommend_substitutes(SubstitutionRequest(
        subject_id="db", department_id="cse", candidates=[
            Candidate(id="ok", name="A", department_id="cse", qualified=True, available=True,
                      remaining_periods=5, max_weekly_periods=20, section_familiarity=80, experience_score=50),
            Candidate(id="busy", name="B", department_id="cse", qualified=True, available=False,
                      remaining_periods=3, max_weekly_periods=20, section_familiarity=100, experience_score=100),
        ],
    ))
    assert [candidate.id for candidate in result.candidates] == ["ok"]
    assert result.excluded_candidate_ids == ["busy"]
    assert result.weights["qualification"] == 40
    assert result.candidates[0].score == 80.75
