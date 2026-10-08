from app.schemas.requirements import (
    ConstraintType,
    TimetableConstraint,
    TimetableRequirement,
    SubjectRequirement,
)


def test_subject_requirement():
    subject = SubjectRequirement(
        subject="Mathematics",
        classes_per_week=4,
        teacher="Dr Sharma",
    )

    assert subject.subject == "Mathematics"
    assert subject.classes_per_week == 4
    assert subject.teacher == "Dr Sharma"


def test_timetable_constraint():
    constraint = TimetableConstraint(
        type=ConstraintType.MAX_CLASSES_PER_DAY,
        subject="Mathematics",
        value=1,
    )

    assert constraint.type == ConstraintType.MAX_CLASSES_PER_DAY
    assert constraint.subject == "Mathematics"
    assert constraint.value == 1


def test_timetable_requirement():
    requirement = TimetableRequirement(
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
            )
        ],
    )

    assert requirement.section == "CSE A"
    assert len(requirement.subjects) == 1
    assert len(requirement.constraints) == 1