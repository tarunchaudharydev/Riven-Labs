from app.schemas.requirements import (
    ConstraintType,
    SubjectRequirement,
    TimetableConstraint,
    TimetableRequirement,
)


class MockProvider:
    async def parse_timetable_requirement(
        self,
        user_input: str,
    ) -> TimetableRequirement:
        return TimetableRequirement(
            section="CSE A",
            subjects=[
                SubjectRequirement(
                    subject="Mathematics",
                    classes_per_week=4,
                    teacher="Dr Sharma",
                )
            ],
            constraints=[
                TimetableConstraint(
                    type=ConstraintType.PREFERRED_TIME,
                    subject="Mathematics",
                    value="morning",
                ),
                TimetableConstraint(
                    type=ConstraintType.MAX_CLASSES_PER_DAY,
                    subject="Mathematics",
                    value=1,
                ),
            ],
        )
        