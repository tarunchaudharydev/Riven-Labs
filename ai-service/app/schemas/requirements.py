from enum import Enum

from pydantic import BaseModel, Field


class TimePreference(str, Enum):
    MORNING = "MORNING"
    AFTERNOON = "AFTERNOON"
    EVENING = "EVENING"
    BEFORE_LUNCH = "BEFORE_LUNCH"
    AFTER_LUNCH = "AFTER_LUNCH"
    NONE = "NONE"


class ConstraintType(str, Enum):
    PREFERRED_TIME = "PREFERRED_TIME"
    MAX_CLASSES_PER_DAY = "MAX_CLASSES_PER_DAY"
    MIN_CLASSES_PER_DAY = "MIN_CLASSES_PER_DAY"
    AVOID_DAY = "AVOID_DAY"
    PREFERRED_DAY = "PREFERRED_DAY"
    CONSECUTIVE_CLASSES = "CONSECUTIVE_CLASSES"
    AVOID_PERIOD = "AVOID_PERIOD"


class SubjectRequirement(BaseModel):
    subject: str = Field(description="Name of the subject")
    classes_per_week: int = Field(
        description="Number of classes required per week",
        ge=1,
    )
    teacher: str | None = Field(
        default=None,
        description="Teacher assigned to the subject, if specified",
    )


class TimetableConstraint(BaseModel):
    type: ConstraintType
    subject: str | None = None
    value: str | int | bool


class TimetableRequirement(BaseModel):
    section: str
    subjects: list[SubjectRequirement]
    constraints: list[TimetableConstraint] = []