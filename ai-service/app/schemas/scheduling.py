from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class TimeSlot(StrictModel):
    id: str
    day: str
    period: int = Field(ge=1, le=24)
    label: str | None = None
    is_teaching_period: bool = True


class Section(StrictModel):
    id: str
    name: str
    student_count: int = Field(ge=0, le=5000)
    department_id: str | None = None


class Faculty(StrictModel):
    id: str
    name: str
    department_id: str
    qualified_subject_ids: list[str] = Field(default_factory=list)
    unavailable_slot_ids: list[str] = Field(default_factory=list)
    preferred_slot_ids: list[str] = Field(default_factory=list)
    max_weekly_periods: int = Field(ge=1, le=80)
    active: bool = True


class Room(StrictModel):
    id: str
    name: str
    capacity: int = Field(ge=0, le=10000)
    room_type: str = "CLASSROOM"
    equipment: list[str] = Field(default_factory=list)
    unavailable_slot_ids: list[str] = Field(default_factory=list)


class TeachingRequirement(StrictModel):
    @model_validator(mode="before")
    @classmethod
    def default_session_duration(cls, value: Any) -> Any:
        if isinstance(value, dict) and value.get("periods_per_session") is None:
            room_type = str(value.get("room_type", "CLASSROOM")).upper()
            value = {**value, "periods_per_session": 2 if "LAB" in room_type else 1}
        return value

    id: str
    department_id: str | None = None
    subject_id: str
    subject_name: str
    section_id: str
    sessions_per_week: int = Field(ge=1, le=30)
    faculty_ids: list[str] = Field(min_length=1, max_length=100)
    room_type: str = "CLASSROOM"
    required_equipment: list[str] = Field(default_factory=list)
    periods_per_session: int = Field(default=1, ge=1, le=6)
    preferred_faculty_ids: list[str] = Field(default_factory=list)
    preferred_slot_ids: list[str] = Field(default_factory=list)
    excluded_slot_ids: list[str] = Field(default_factory=list)
    max_sessions_per_day: int | None = Field(default=None, ge=1, le=12)


class SchedulingProblem(StrictModel):
    term_id: str
    time_slots: list[TimeSlot] = Field(min_length=1, max_length=300)
    sections: list[Section] = Field(min_length=1, max_length=200)
    faculty: list[Faculty] = Field(min_length=1, max_length=300)
    rooms: list[Room] = Field(min_length=1, max_length=300)
    requirements: list[TeachingRequirement] = Field(min_length=1, max_length=3000)
    time_limit_seconds: float = Field(default=15, ge=1, le=120)

    @model_validator(mode="after")
    def validate_references(self):
        slot_ids = [slot.id for slot in self.time_slots]
        section_ids = {section.id for section in self.sections}
        faculty_ids = {member.id for member in self.faculty}
        if len(slot_ids) != len(set(slot_ids)):
            raise ValueError("Time slot ids must be unique")
        day_periods = [(slot.day, slot.period) for slot in self.time_slots]
        if len(day_periods) != len(set(day_periods)):
            raise ValueError("There can only be one time slot per day and period")
        for label, values in (("section", self.sections), ("faculty", self.faculty), ("room", self.rooms), ("requirement", self.requirements)):
            ids = [item.id for item in values]
            if len(ids) != len(set(ids)):
                raise ValueError(f"{label.title()} ids must be unique")
        room_ids = {room.id for room in self.rooms}
        if not room_ids:
            raise ValueError("At least one room is required")
        for req in self.requirements:
            if req.section_id not in section_ids or not set(req.faculty_ids) <= faculty_ids:
                raise ValueError(f"Requirement {req.id} references an unknown section or faculty member")
        return self


class ScheduleEntry(StrictModel):
    requirement_id: str
    department_id: str | None = None
    subject_id: str
    subject_name: str
    section_id: str
    faculty_id: str
    room_id: str
    day: str
    period: int
    duration: int
    slot_ids: list[str]


class Diagnostic(StrictModel):
    code: str
    severity: str
    message: str
    entity_ids: list[str] = Field(default_factory=list)


class ValidationSummary(StrictModel):
    valid: bool
    diagnostics: list[Diagnostic] = Field(default_factory=list)


class ScheduleResult(StrictModel):
    status: str
    entries: list[ScheduleEntry] = Field(default_factory=list)
    diagnostics: list[Diagnostic] = Field(default_factory=list)
    validation: ValidationSummary
    objective_value: int | None = None
    best_objective_bound: int | None = None
