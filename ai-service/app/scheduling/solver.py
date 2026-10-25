from collections import defaultdict
from dataclasses import dataclass

from ortools.sat.python import cp_model

from app.schemas.scheduling import (
    Diagnostic,
    ScheduleEntry,
    ScheduleResult,
    SchedulingProblem,
)
from app.scheduling.validation import validate_schedule

MAX_MODEL_OPTIONS = 200_000


@dataclass(frozen=True)
class Option:
    requirement_id: str
    section_id: str
    faculty_id: str
    room_id: str
    start_slot_id: str
    slot_ids: tuple[str, ...]
    day: str
    period: int
    preference_cost: int


def solve_timetable(problem: SchedulingProblem) -> ScheduleResult:
    model = cp_model.CpModel()
    section_map = {section.id: section for section in problem.sections}
    faculty_map = {person.id: person for person in problem.faculty}
    slots_by_day: dict[str, list] = defaultdict(list)
    for slot in problem.time_slots:
        if slot.is_teaching_period:
            slots_by_day[slot.day].append(slot)
    for day in slots_by_day:
        slots_by_day[day].sort(key=lambda item: (item.period, item.id))

    options: list[Option] = []
    option_vars: list[cp_model.IntVar] = []
    by_requirement: dict[str, list[cp_model.IntVar]] = defaultdict(list)
    resource_slots: dict[tuple[str, str], list[cp_model.IntVar]] = defaultdict(list)
    requirement_day_vars: dict[tuple[str, str], list[cp_model.IntVar]] = defaultdict(list)
    faculty_workload: dict[str, list[tuple[int, cp_model.IntVar]]] = defaultdict(list)
    repeated_day_penalties: list[cp_model.IntVar] = []

    for req in problem.requirements:
        section = section_map[req.section_id]
        for day, day_slots in slots_by_day.items():
            for index, start in enumerate(day_slots):
                covered = day_slots[index:index + req.periods_per_session]
                if len(covered) != req.periods_per_session or any(
                    b.period != a.period + 1 for a, b in zip(covered, covered[1:])
                ):
                    continue
                covered_ids = tuple(slot.id for slot in covered)
                if set(covered_ids) & set(req.excluded_slot_ids):
                    continue
                for faculty_id in req.faculty_ids:
                    teacher = faculty_map[faculty_id]
                    if not teacher.active or req.subject_id not in teacher.qualified_subject_ids:
                        continue
                    if set(covered_ids) & set(teacher.unavailable_slot_ids):
                        continue
                    for room in problem.rooms:
                        if room.capacity < section.student_count or room.room_type != req.room_type:
                            continue
                        if not set(req.required_equipment) <= set(room.equipment):
                            continue
                        if set(covered_ids) & set(room.unavailable_slot_ids):
                            continue
                        soft_cost = 0
                        if req.preferred_slot_ids and start.id not in req.preferred_slot_ids:
                            soft_cost += 20
                        if req.preferred_faculty_ids and faculty_id not in req.preferred_faculty_ids:
                            soft_cost += 12
                        if teacher.preferred_slot_ids and start.id not in teacher.preferred_slot_ids:
                            soft_cost += 8
                        if start.period == max(slot.period for slot in day_slots):
                            soft_cost += 3
                        option = Option(req.id, req.section_id, faculty_id, room.id, start.id,
                                        covered_ids, day, start.period, soft_cost)
                        var = model.new_bool_var(f"x_{len(options)}")
                        options.append(option)
                        option_vars.append(var)
                        by_requirement[req.id].append(var)
                        requirement_day_vars[(req.id, day)].append(var)
                        for slot_id in covered_ids:
                            for resource in (f"section:{req.section_id}", f"faculty:{faculty_id}", f"room:{room.id}"):
                                resource_slots[(resource, slot_id)].append(var)
                        faculty_workload[faculty_id].append((req.periods_per_session, var))
                        if len(options) >= MAX_MODEL_OPTIONS:
                            diagnostic = Diagnostic(code="MODEL_TOO_LARGE", severity="ERROR",
                                                    message="The scheduling problem has too many valid assignment options for the configured solver limit.",
                                                    entity_ids=[req.id])
                            return ScheduleResult(status="REJECTED", diagnostics=[diagnostic],
                                                  validation={"valid": False, "diagnostics": [diagnostic]})

    for req in problem.requirements:
        model.add(sum(by_requirement[req.id]) == req.sessions_per_week)
    for variables in resource_slots.values():
        model.add_at_most_one(variables)
    for req in problem.requirements:
        for day in slots_by_day:
            daily = requirement_day_vars[(req.id, day)]
            if req.max_sessions_per_day is not None:
                model.add(sum(daily) <= req.max_sessions_per_day)
            elif len(daily) > 1:
                count = model.new_int_var(0, len(daily), f"day_count_{req.id}_{day}")
                excess = model.new_int_var(0, len(daily), f"day_repeat_{req.id}_{day}")
                model.add(count == sum(daily))
                model.add_max_equality(excess, [count - 1, 0])
                repeated_day_penalties.append(excess)
    for teacher_id, loads in faculty_workload.items():
        teacher = faculty_map[teacher_id]
        model.add(sum(periods * var for periods, var in loads) <= teacher.max_weekly_periods)

    objective_terms = []
    objective_terms.extend(option.preference_cost * var for option, var in zip(options, option_vars))
    objective_terms.extend(3 * penalty for penalty in repeated_day_penalties)
    if objective_terms:
        model.minimize(sum(objective_terms))

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = min(problem.time_limit_seconds, 120)
    solver.parameters.num_search_workers = 1
    solver.parameters.random_seed = 0
    solver.parameters.max_deterministic_time = min(problem.time_limit_seconds, 120)
    status = solver.solve(model)
    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        code = "INFEASIBLE" if status == cp_model.INFEASIBLE else "NO_SOLUTION"
        msg = "The hard constraints are infeasible." if status == cp_model.INFEASIBLE else "The solver stopped before finding a feasible timetable."
        diag = Diagnostic(code=code, severity="ERROR", message=msg, entity_ids=[r.id for r in problem.requirements])
        return ScheduleResult(status="INFEASIBLE" if status == cp_model.INFEASIBLE else "UNKNOWN",
                              diagnostics=[diag], validation={"valid": False, "diagnostics": [diag]})

    entries: list[ScheduleEntry] = []
    for option, var in zip(options, option_vars):
        if not solver.boolean_value(var):
            continue
        req = next(r for r in problem.requirements if r.id == option.requirement_id)
        entries.append(ScheduleEntry(
            requirement_id=req.id, department_id=req.department_id, subject_id=req.subject_id, subject_name=req.subject_name,
            section_id=req.section_id, faculty_id=option.faculty_id, room_id=option.room_id,
            day=option.day, period=option.period, duration=req.periods_per_session,
            slot_ids=list(option.slot_ids),
        ))
    entries.sort(key=lambda item: (item.day, item.period, item.section_id, item.subject_name))
    validation = validate_schedule(problem, entries)
    diagnostics = validation.diagnostics
    if not validation.valid:
        return ScheduleResult(status="VALIDATION_FAILED", entries=[], diagnostics=diagnostics,
                              validation=validation, objective_value=int(solver.objective_value),
                              best_objective_bound=int(solver.best_objective_bound))
    return ScheduleResult(status="OPTIMAL" if status == cp_model.OPTIMAL else "FEASIBLE",
                          entries=entries, diagnostics=[], validation=validation,
                          objective_value=int(solver.objective_value),
                          best_objective_bound=int(solver.best_objective_bound))
