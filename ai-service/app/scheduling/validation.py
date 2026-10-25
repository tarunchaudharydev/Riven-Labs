from collections import Counter, defaultdict

from app.schemas.scheduling import (
    Diagnostic,
    ScheduleEntry,
    SchedulingProblem,
    ValidationSummary,
)


def validate_schedule(problem: SchedulingProblem, entries: list[ScheduleEntry]) -> ValidationSummary:
    diagnostics: list[Diagnostic] = []
    slots = {slot.id: slot for slot in problem.time_slots}
    sections = {section.id: section for section in problem.sections}
    faculty = {member.id: member for member in problem.faculty}
    rooms = {room.id: room for room in problem.rooms}
    requirements = {req.id: req for req in problem.requirements}
    occupancy: dict[tuple[str, str], str] = {}
    actual_counts: Counter[str] = Counter()
    daily_counts: Counter[tuple[str, str]] = Counter()

    def issue(code: str, message: str, ids: list[str]) -> None:
        diagnostics.append(Diagnostic(code=code, severity="ERROR", message=message, entity_ids=ids))

    for entry in entries:
        req = requirements.get(entry.requirement_id)
        section = sections.get(entry.section_id)
        teacher = faculty.get(entry.faculty_id)
        room = rooms.get(entry.room_id)
        if not all((req, section, teacher, room)):
            issue("UNKNOWN_REFERENCE", "An assignment references an unknown requirement, section, faculty member, or room.", [entry.requirement_id])
            continue
        actual_counts[req.id] += 1
        daily_counts[(req.id, entry.day)] += 1
        if entry.subject_id != req.subject_id or entry.section_id != req.section_id:
            issue("REQUIREMENT_MISMATCH", "Assignment subject or section does not match its teaching requirement.", [req.id])
        if entry.faculty_id not in req.faculty_ids or req.subject_id not in teacher.qualified_subject_ids or not teacher.active:
            issue("UNQUALIFIED_FACULTY", "Assigned faculty member is inactive or not authorized for this subject.", [teacher.id, req.id])
        if room.capacity < section.student_count or room.room_type != req.room_type or not set(req.required_equipment) <= set(room.equipment):
            issue("INCOMPATIBLE_ROOM", "Assigned room does not meet the room type, capacity, or equipment requirements.", [room.id, req.id])
        if len(entry.slot_ids) != req.periods_per_session or entry.duration != req.periods_per_session:
            issue("INVALID_DURATION", "The assignment duration does not match the required block length.", [req.id])
        covered = [slots.get(slot_id) for slot_id in entry.slot_ids]
        if any(slot is None for slot in covered):
            issue("UNKNOWN_TIME_SLOT", "An assignment references an unknown time slot.", [req.id])
            continue
        ordered = sorted(covered, key=lambda slot: slot.period)
        if any(not slot.is_teaching_period for slot in ordered):
            issue("BLOCKED_PERIOD", "An assignment uses a break or blocked period.", [req.id])
        if len({slot.day for slot in ordered}) != 1 or any(b.period != a.period + 1 for a, b in zip(ordered, ordered[1:])):
            issue("NON_CONTIGUOUS_BLOCK", "A multi-period class must occupy contiguous periods on one day.", [req.id])
        if ordered and (ordered[0].day != entry.day or ordered[0].period != entry.period):
            issue("SLOT_MISMATCH", "Assignment day or period does not match its time slots.", [req.id])
        if set(entry.slot_ids) & (set(req.excluded_slot_ids) | set(teacher.unavailable_slot_ids) | set(room.unavailable_slot_ids)):
            issue("AVAILABILITY_VIOLATION", "Assignment uses a faculty-unavailable, room-unavailable, or excluded period.", [teacher.id, room.id, req.id])
        for slot_id in entry.slot_ids:
            for resource_id in (f"section:{section.id}", f"faculty:{teacher.id}", f"room:{room.id}"):
                key = (resource_id, slot_id)
                if key in occupancy:
                    issue("DOUBLE_BOOKING", "A section, faculty member, or room is scheduled more than once in the same period.", [resource_id, occupancy[key], req.id])
                occupancy[key] = req.id

    for req in problem.requirements:
        if actual_counts[req.id] != req.sessions_per_week:
            issue("WEEKLY_COUNT", f"Requirement {req.id} has {actual_counts[req.id]} sessions; expected {req.sessions_per_week}.", [req.id])
        if req.max_sessions_per_day is not None:
            for (requirement_id, day), count in daily_counts.items():
                if requirement_id == req.id and count > req.max_sessions_per_day:
                    issue("DAILY_LIMIT", f"Requirement {req.id} exceeds its daily session limit on {day}.", [req.id])

    teacher_load: Counter[str] = Counter()
    for entry in entries:
        if entry.faculty_id in faculty:
            teacher_load[entry.faculty_id] += entry.duration
    for teacher_id, periods in teacher_load.items():
        if periods > faculty[teacher_id].max_weekly_periods:
            issue("FACULTY_WORKLOAD", "Assigned periods exceed the faculty member's weekly limit.", [teacher_id])
    return ValidationSummary(valid=not diagnostics, diagnostics=diagnostics)
