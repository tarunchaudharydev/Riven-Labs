from app.scheduling.solver import solve_timetable
from app.schemas.scheduling import Faculty, Section, SchedulingProblem, TeachingRequirement, TimeSlot


def base_problem() -> SchedulingProblem:
    return SchedulingProblem.model_validate({
        "term_id": "term-1",
        "time_slots": [
            {"id": "m1", "day": "MON", "period": 1},
            {"id": "m2", "day": "MON", "period": 2},
            {"id": "t1", "day": "TUE", "period": 1},
        ],
        "sections": [{"id": "sec", "name": "CSE A", "student_count": 30}],
        "faculty": [{"id": "f1", "name": "Dr Rao", "department_id": "cse",
                     "qualified_subject_ids": ["db"], "max_weekly_periods": 10}],
        "rooms": [{"id": "r1", "name": "R1", "capacity": 40, "room_type": "CLASSROOM"}],
        "requirements": [{"id": "req-1", "subject_id": "db", "subject_name": "Databases",
                          "section_id": "sec", "sessions_per_week": 2, "faculty_ids": ["f1"]}],
    })


def test_solver_returns_independently_valid_schedule():
    result = solve_timetable(base_problem())
    assert result.status in {"OPTIMAL", "FEASIBLE"}
    assert len(result.entries) == 2
    assert result.validation.valid


def test_solver_rejects_unavailable_and_unqualified_options():
    problem = base_problem()
    problem.faculty[0].unavailable_slot_ids = ["m1", "m2", "t1"]
    result = solve_timetable(problem)
    assert result.status == "INFEASIBLE"
    assert not result.validation.valid


def test_solver_respects_contiguous_lab_blocks():
    problem = base_problem()
    problem.requirements[0].periods_per_session = 2
    problem.requirements[0].sessions_per_week = 1
    result = solve_timetable(problem)
    assert result.status in {"OPTIMAL", "FEASIBLE"}
    assert result.entries[0].slot_ids == ["m1", "m2"]


def test_solver_prevents_section_double_booking():
    problem = base_problem()
    problem.time_slots = [TimeSlot(id="m1", day="MON", period=1)]
    problem.requirements[0].sessions_per_week = 1
    problem.requirements.append(TeachingRequirement.model_validate({"id": "req-2", "subject_id": "db", "subject_name": "Databases",
                                 "section_id": "sec", "sessions_per_week": 1, "faculty_ids": ["f1"]}))
    assert solve_timetable(problem).status == "INFEASIBLE"


def test_solver_prevents_faculty_double_booking():
    problem = base_problem()
    problem.time_slots = [TimeSlot(id="m1", day="MON", period=1)]
    problem.sections.append(Section(id="sec-b", name="CSE B", student_count=25))
    problem.requirements = [TeachingRequirement.model_validate(item) for item in [
        {"id": "req-a", "subject_id": "db", "subject_name": "Databases", "section_id": "sec",
         "sessions_per_week": 1, "faculty_ids": ["f1"]},
        {"id": "req-b", "subject_id": "db", "subject_name": "Databases", "section_id": "sec-b",
         "sessions_per_week": 1, "faculty_ids": ["f1"]},
    ]]
    assert solve_timetable(problem).status == "INFEASIBLE"


def test_solver_prevents_room_double_booking():
    problem = base_problem()
    problem.time_slots = [TimeSlot(id="m1", day="MON", period=1)]
    problem.faculty.append(Faculty(id="f2", name="Dr Sen", department_id="cse",
                            qualified_subject_ids=["db"], max_weekly_periods=10))
    problem.sections.append(Section(id="sec-b", name="CSE B", student_count=25))
    problem.requirements = [TeachingRequirement.model_validate(item) for item in [
        {"id": "req-a", "subject_id": "db", "subject_name": "Databases", "section_id": "sec",
         "sessions_per_week": 1, "faculty_ids": ["f1"]},
        {"id": "req-b", "subject_id": "db", "subject_name": "Databases", "section_id": "sec-b",
         "sessions_per_week": 1, "faculty_ids": ["f2"]},
    ]]
    assert solve_timetable(problem).status == "INFEASIBLE"


def test_solver_rejects_unqualified_faculty_and_incompatible_rooms():
    problem = base_problem()
    problem.faculty[0].qualified_subject_ids = []
    assert solve_timetable(problem).status == "INFEASIBLE"
    problem = base_problem()
    problem.rooms[0].capacity = 20
    assert solve_timetable(problem).status == "INFEASIBLE"
    problem = base_problem()
    problem.requirements[0].room_type = "LAB"
    assert solve_timetable(problem).status == "INFEASIBLE"
    problem = base_problem()
    problem.requirements[0].required_equipment = ["projector"]
    assert solve_timetable(problem).status == "INFEASIBLE"


def test_solver_respects_room_availability_and_workload_limits():
    problem = base_problem()
    problem.rooms[0].unavailable_slot_ids = ["m1", "m2", "t1"]
    assert solve_timetable(problem).status == "INFEASIBLE"
    problem = base_problem()
    problem.faculty[0].max_weekly_periods = 1
    assert solve_timetable(problem).status == "INFEASIBLE"


def test_solver_does_not_use_breaks_for_contiguous_lab_blocks():
    problem = base_problem()
    problem.time_slots[1].is_teaching_period = False
    problem.requirements[0].periods_per_session = 2
    problem.requirements[0].sessions_per_week = 1
    assert solve_timetable(problem).status == "INFEASIBLE"
