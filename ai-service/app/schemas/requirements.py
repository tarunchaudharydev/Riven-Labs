from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class CatalogEntity(StrictModel):
    id: str = Field(min_length=1, max_length=128)
    name: str = Field(min_length=1, max_length=160)
    code: str | None = Field(default=None, max_length=64)
    aliases: list[str] = Field(default_factory=list, max_length=20)
    department_id: str | None = None
    shift_id: str | None = None


class CatalogTimeSlot(StrictModel):
    id: str = Field(min_length=1, max_length=128)
    day: str = Field(min_length=1, max_length=32)
    period: int = Field(ge=1, le=24)
    label: str | None = None
    start_time: str | None = None
    end_time: str | None = None
    shift_ids: list[str] = Field(default_factory=list)
    is_teaching_period: bool = True


class CatalogShift(StrictModel):
    id: str = Field(min_length=1, max_length=128)
    name: str = Field(min_length=1, max_length=160)
    start_time: str
    end_time: str


class FacultyUnavailability(StrictModel):
    faculty_id: str = Field(min_length=1, max_length=128)
    excluded_slot_ids: list[str] = Field(default_factory=list, max_length=100)


class RequirementCatalog(StrictModel):
    sections: list[CatalogEntity] = Field(default_factory=list, max_length=500)
    subjects: list[CatalogEntity] = Field(default_factory=list, max_length=1000)
    faculty: list[CatalogEntity] = Field(default_factory=list, max_length=1000)
    rooms: list[CatalogEntity] = Field(default_factory=list, max_length=1000)
    time_slots: list[CatalogTimeSlot] = Field(default_factory=list, max_length=500)
    shifts: list[CatalogShift] = Field(default_factory=list, max_length=10)


class RequirementChange(StrictModel):
    subject_id: str = Field(min_length=1, max_length=128)
    section_id: str = Field(min_length=1, max_length=128)
    sessions_per_week: int | None = Field(default=None, ge=1, le=30)
    max_sessions_per_week: int | None = Field(default=None, ge=1, le=30)
    preferred_faculty_ids: list[str] = Field(default_factory=list, max_length=20)
    excluded_slot_ids: list[str] = Field(default_factory=list, max_length=100)
    preferred_slot_ids: list[str] = Field(default_factory=list, max_length=100)
    periods_per_session: int | None = Field(default=None, ge=1, le=6)
    priority: str = Field(default="SOFT", pattern="^(HARD|SOFT)$")

    @model_validator(mode="after")
    def count_is_not_both_exact_and_maximum(self):
        if self.sessions_per_week is not None and self.max_sessions_per_week is not None:
            raise ValueError("Specify an exact weekly count or a maximum, not both")
        return self


class RequirementInterpretation(StrictModel):
    original_text: str = Field(min_length=1, max_length=4000)
    changes: list[RequirementChange] = Field(default_factory=list, max_length=100)
    faculty_unavailability: list[FacultyUnavailability] = Field(default_factory=list, max_length=100)
    clarification_questions: list[str] = Field(default_factory=list, max_length=10)
    unsupported_requirements: list[str] = Field(default_factory=list, max_length=20)
    requires_review: bool = True


class ConversationTurn(StrictModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=4000)


class ParseRequirementRequest(StrictModel):
    text: str = Field(min_length=4, max_length=4000)
    catalog: RequirementCatalog
    conversation: list[ConversationTurn] = Field(default_factory=list, max_length=20)


def validate_catalog_references(result: RequirementInterpretation, catalog: RequirementCatalog) -> RequirementInterpretation:
    subject_ids = {item.id for item in catalog.subjects}
    section_ids = {item.id for item in catalog.sections}
    faculty_ids = {item.id for item in catalog.faculty}
    slot_ids = {item.id for item in catalog.time_slots}
    for change in result.changes:
        if change.subject_id not in subject_ids or change.section_id not in section_ids:
            raise ValueError("The interpretation referenced a subject or section outside the supplied catalog")
        if not set(change.preferred_faculty_ids) <= faculty_ids:
            raise ValueError("The interpretation referenced faculty outside the supplied catalog")
        if not set(change.excluded_slot_ids + change.preferred_slot_ids) <= slot_ids:
            raise ValueError("The interpretation referenced time slots outside the supplied catalog")
    for absence in result.faculty_unavailability:
        if absence.faculty_id not in faculty_ids:
            raise ValueError("The interpretation referenced faculty outside the supplied catalog")
        if not set(absence.excluded_slot_ids) <= slot_ids:
            raise ValueError("The interpretation referenced time slots outside the supplied catalog")
    return result
