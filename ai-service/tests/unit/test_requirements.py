import pytest
from pydantic import ValidationError

from app.schemas.requirements import RequirementChange, RequirementInterpretation


def test_requirement_change_requires_stable_catalog_ids():
    change = RequirementChange(subject_id="db-101", section_id="cse-a", sessions_per_week=3)
    assert change.sessions_per_week == 3
    with pytest.raises(ValidationError):
        RequirementChange(subject_id="", section_id="cse-a")


def test_interpretation_lists_are_not_shared_between_instances():
    first = RequirementInterpretation(original_text="first")
    second = RequirementInterpretation(original_text="second")
    first.clarification_questions.append("Which section?")
    assert second.clarification_questions == []
