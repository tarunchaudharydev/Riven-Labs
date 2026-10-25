import asyncio

import pytest

from app.ai.providers.mock_provider import MockProvider
from app.schemas.requirements import (
    ParseRequirementRequest,
    RequirementCatalog,
    RequirementChange,
    RequirementInterpretation,
)
from app.services.requirement_service import RequirementService


def catalog():
    return RequirementCatalog.model_validate({
        "sections": [{"id": "sec-a", "name": "CSE A"}],
        "subjects": [{"id": "db", "name": "Databases"}],
        "faculty": [{"id": "fac-1", "name": "Dr Rao"}],
        "time_slots": [{"id": "mon-1", "day": "MON", "period": 1}],
    })


def test_requirement_service_validates_all_references_against_catalog():
    parsed = RequirementInterpretation(original_text="request", changes=[
        RequirementChange(subject_id="db", section_id="sec-a", preferred_faculty_ids=["fac-1"], excluded_slot_ids=["mon-1"])
    ])
    service = RequirementService(MockProvider(parsed))
    result = asyncio.run(service.parse(ParseRequirementRequest(text="Avoid Monday period one", catalog=catalog())))
    assert result.original_text == "Avoid Monday period one"
    assert result.changes[0].excluded_slot_ids == ["mon-1"]


def test_requirement_service_rejects_model_invented_ids():
    parsed = RequirementInterpretation(original_text="request", changes=[
        RequirementChange(subject_id="made-up", section_id="sec-a")
    ])
    service = RequirementService(MockProvider(parsed))
    with pytest.raises(ValueError):
        asyncio.run(service.parse(ParseRequirementRequest(text="Schedule Databases", catalog=catalog())))
