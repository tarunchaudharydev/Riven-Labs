package com.rivenlabs.timetable.api;

import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import com.rivenlabs.timetable.security.RoleGuard;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalTime;
import java.util.*;

/** Institution-scoped academic catalog backed by Firebase Realtime Database. */
@RestController
@RequestMapping("/api/v1/academics")
public class AcademicDataController {
    private static final Set<String> COLLECTIONS = Set.of(
            "departments", "academicTerms", "faculty", "subjects", "sections", "rooms");
    private static final Set<String> WRITE_ROLES = Set.of("SUPER_ADMIN", "DEPARTMENT_ADMIN");
    private final RealtimeDatabaseStore database;
    private final RoleGuard roles;

    public AcademicDataController(RealtimeDatabaseStore database, RoleGuard roles) {
        this.database = database;
        this.roles = roles;
    }

    @GetMapping("/terms/{termId}/scheduling-inputs")
    public Map<String, Object> schedulingInputs(@AuthenticationPrincipal AppPrincipal principal,
                                                @PathVariable String termId) {
        requireAcademicViewer(principal);
        Map<String, Object> inputs = database.readMap(database.tenantPath(principal.institutionId(), "schedulingInputs", termId));
        if (!"DEPARTMENT_ADMIN".equals(principal.role())) return inputs;
        String departmentId = principal.departmentId();
        inputs.put("sections", filterInputRows(inputs.get("sections"), departmentId, false, false));
        inputs.put("requirements", filterInputRows(inputs.get("requirements"), departmentId, false, false));
        inputs.put("faculty", filterInputRows(inputs.get("faculty"), departmentId, true, false));
        inputs.put("rooms", filterInputRows(inputs.get("rooms"), departmentId, false, true));
        return inputs;
    }

    @PutMapping("/terms/{termId}/scheduling-inputs")
    public Map<String, Object> saveSchedulingInputs(@AuthenticationPrincipal AppPrincipal principal,
                                                   @PathVariable String termId,
                                                   @RequestBody Map<String, Object> body) {
        if (!"SUPER_ADMIN".equals(principal.role()) && !"TIMETABLE_INCHARGE".equals(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account cannot change timetable inputs.");
        }
        validKey(termId);
        Map<String, Object> inputs = new LinkedHashMap<>();
        Object rawShifts = body.get("shifts");
        if (rawShifts != null && !(rawShifts instanceof Collection<?>)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shifts must be an array when provided.");
        }
        List<Map<String, Object>> shifts = objectList(rawShifts);
        if (!shifts.isEmpty()) {
            if (shifts.size() != 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configure exactly two shifts for this institution.");
            uniqueIds(shifts, "shifts");
            Set<String> configuredShiftIds = new HashSet<>();
            for (Map<String, Object> shift : shifts) configuredShiftIds.add(Objects.toString(shift.get("id")));
            if (!configuredShiftIds.equals(Set.of("shift-1", "shift-2"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use the fixed shift IDs shift-1 and shift-2.");
            }
            for (Map<String, Object> shift : shifts) {
                requiredText(shift, "name");
                LocalTime start = parseTime(shift.get("start_time"), "Shift start time");
                LocalTime end = parseTime(shift.get("end_time"), "Shift end time");
                if (!start.isBefore(end) || start.getMinute() != 0 || end.getMinute() != 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shift times must be whole-hour values and the end must follow the start.");
                }
            }
            inputs.put("shifts", shifts);
        }
        for (String field : List.of("time_slots", "sections", "faculty", "rooms", "requirements")) {
            Object value = body.get(field);
            if (!(value instanceof Collection<?> collection)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be an array.");
            }
            if (collection.size() > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " exceeds the allowed record count.");
            inputs.put(field, collection);
        }
        List<Map<String, Object>> slots = objectList(inputs.get("time_slots"));
        List<Map<String, Object>> sections = objectList(inputs.get("sections"));
        List<Map<String, Object>> faculty = objectList(inputs.get("faculty"));
        List<Map<String, Object>> rooms = objectList(inputs.get("rooms"));
        List<Map<String, Object>> requirements = objectList(inputs.get("requirements"));
        if (slots.isEmpty() || sections.isEmpty() || faculty.isEmpty() || rooms.isEmpty() || requirements.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one time slot, section, faculty member, room, and teaching requirement before saving.");
        }
        uniqueIds(slots, "time_slots"); uniqueIds(sections, "sections"); uniqueIds(faculty, "faculty"); uniqueIds(rooms, "rooms"); uniqueIds(requirements, "requirements");
        Map<String, Map<String, Object>> shiftsById = new HashMap<>();
        for (Map<String, Object> shift : shifts) shiftsById.put(Objects.toString(shift.get("id")), shift);
        Set<String> dayPeriods = new HashSet<>();
        for (Map<String, Object> item : slots) {
            requiredText(item, "id"); requiredText(item, "day");
            int period = number(item.get("period"));
            if (period < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every teaching slot needs a positive period number.");
            if (!dayPeriods.add(item.get("day") + ":" + period)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There can only be one slot per day and period.");
            }
            if (!shifts.isEmpty()) validateShiftSlot(item, shiftsById);
        }
        if (!shifts.isEmpty()) {
            for (Map<String, Object> section : sections) {
                String shiftId = requiredText(section, "shift_id");
                if (!shiftsById.containsKey(shiftId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each section must use one of the configured shifts.");
                }
            }
        }
        for (Map<String, Object> item : requirements) {
            requiredText(item, "subject_id"); requiredText(item, "section_id");
            if (number(item.get("sessions_per_week")) < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every requirement needs at least one session per week.");
            if (number(item.getOrDefault("periods_per_session", 1)) < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Periods per session must be positive.");
        }
        inputs.put("updatedAt", Instant.now().toString());
        inputs.put("updatedBy", principal.uid());
        database.write(database.tenantPath(principal.institutionId(), "schedulingInputs", termId), inputs);
        audit(principal, "SCHEDULING_INPUTS_SAVED", "schedulingInputs", termId);
        return inputs;
    }

    @GetMapping("/{collection}")
    public Map<String, Object> list(@AuthenticationPrincipal AppPrincipal principal,
                                    @PathVariable String collection) {
        requireAcademicViewer(principal);
        String path = collectionPath(principal, collection);
        Map<String, Object> values = database.readMap(path);
        if ("faculty".equals(collection)) {
            Map<String, Object> departments = database.readMap(database.tenantPath(principal.institutionId(), "departments"));
            Map<String, Object> enriched = new LinkedHashMap<>();
            values.forEach((id, value) -> {
                if (!(value instanceof Map<?, ?> raw)) return;
                Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) raw);
                item.putIfAbsent("id", id);
                Map<String, Object> department = asMap(departments.get(Objects.toString(item.get("department_id"), "")));
                item.put("department_name", Objects.toString(department.get("name"), "Unassigned department"));
                item.put("department_code", Objects.toString(department.get("code"), ""));
                item.put("department_block_no", blockNumber(department.get("block_no")));
                enriched.put(id, item);
            });
            values = enriched;
        }
        if (!"DEPARTMENT_ADMIN".equals(principal.role())) return values;
        Map<String, Object> scoped = new LinkedHashMap<>();
        if ("departments".equals(collection)) {
            Object ownDepartment = values.get(principal.departmentId());
            if (ownDepartment != null) scoped.put(principal.departmentId(), ownDepartment);
            return scoped;
        }
        values.forEach((id, value) -> {
            if (!(value instanceof Map<?, ?> raw)) return;
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) raw);
            item.putIfAbsent("id", id);
            if ("faculty".equals(collection)) {
                scoped.put(id, item);
            } else if ("rooms".equals(collection)
                    && (item.get("department_id") == null || principal.departmentId().equals(item.get("department_id")))) {
                scoped.put(id, item);
            } else if (Set.of("sections", "subjects").contains(collection)
                    && principal.departmentId().equals(item.get("department_id"))) {
                scoped.put(id, item);
            } else if (!Set.of("departments", "faculty", "rooms", "sections", "subjects").contains(collection)) {
                scoped.put(id, value);
            }
        });
        return scoped;
    }

    @PutMapping("/{collection}/{id}")
    public Map<String, Object> save(@AuthenticationPrincipal AppPrincipal principal,
                                    @PathVariable String collection,
                                    @PathVariable String id,
                                    @RequestBody Map<String, Object> body) {
        requireWriter(principal, collection);
        validKey(id);
        Map<String, Object> record = new LinkedHashMap<>(body);
        record.remove("createdAt");
        record.remove("createdBy");
        record.remove("updatedAt");
        record.remove("updatedBy");
        record.put("id", id);
        String recordPath = database.tenantPath(principal.institutionId(), collection, id);
        Map<String, Object> previous = database.readMap(recordPath);
        ensureDepartmentScope(principal, collection, previous);
        validateRecord(principal, collection, record, previous.isEmpty());
        record.put("createdAt", previous.getOrDefault("createdAt", Instant.now().toString()));
        record.put("createdBy", previous.getOrDefault("createdBy", principal.uid()));
        record.put("updatedAt", Instant.now().toString());
        record.put("updatedBy", principal.uid());
        database.write(recordPath, record);
        audit(principal, "ACADEMIC_RECORD_SAVED", collection, id);
        return record;
    }

    @DeleteMapping("/{collection}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppPrincipal principal,
                       @PathVariable String collection,
                       @PathVariable String id) {
        requireWriter(principal, collection);
        validKey(id);
        String path = database.tenantPath(principal.institutionId(), collection, id);
        Map<String, Object> existing = database.readMap(path);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Academic record not found.");
        ensureDepartmentScope(principal, collection, existing);
        database.write(path, null);
        audit(principal, "ACADEMIC_RECORD_DELETED", collection, id);
    }

    private String collectionPath(AppPrincipal principal, String collection) {
        requireCollection(collection);
        return database.tenantPath(principal.institutionId(), collection);
    }

    private void requireWriter(AppPrincipal principal, String collection) {
        requireCollection(collection);
        if (!WRITE_ROLES.contains(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account cannot edit academic records.");
        }
        if ("DEPARTMENT_ADMIN".equals(principal.role()) && "departments".equals(collection)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only super administrators can manage departments.");
        }
        if ("DEPARTMENT_ADMIN".equals(principal.role()) && principal.departmentId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account is missing its department assignment.");
        }
    }

    private void requireAcademicViewer(AppPrincipal principal) {
        if (!Set.of("SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER").contains(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account cannot view the academic catalog.");
        }
        if ("DEPARTMENT_ADMIN".equals(principal.role()) && principal.departmentId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account is missing its department assignment.");
        }
    }

    private void requireCollection(String collection) {
        if (!COLLECTIONS.contains(collection)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown academic collection.");
        }
    }

    private void validateRecord(AppPrincipal principal, String collection, Map<String, Object> record, boolean creating) {
        String label = Objects.toString(record.get("name"), "").trim();
        if (label.isBlank() || label.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A name between 1 and 160 characters is required.");
        }
        if ("DEPARTMENT_ADMIN".equals(principal.role()) && !"departments".equals(collection)) {
            Object requested = record.get("department_id");
            if (requested == null && principal.departmentId() != null) {
                record.put("department_id", principal.departmentId());
            } else if (principal.departmentId() != null && !principal.departmentId().equals(requested)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Department administrators can only edit records in their department.");
            }
        }
        if ("faculty".equals(collection) || "subjects".equals(collection) || "sections".equals(collection)) {
            requiredText(record, "department_id");
        }
        if ("sections".equals(collection)) {
            String shiftId = Objects.toString(record.get("shift_id"), "");
            if (!Set.of("shift-1", "shift-2").contains(shiftId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose Shift 1 or Shift 2 for this section.");
            }
        }
        if ("rooms".equals(collection)) {
            int capacity = number(record.get("capacity"));
            if (capacity < 1 || capacity > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room capacity must be between 1 and 10000.");
            requiredText(record, "room_type");
        }
        if ("faculty".equals(collection)) {
            int maxPeriods = number(record.get("max_weekly_periods"));
            if (maxPeriods < 1 || maxPeriods > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Weekly faculty periods must be between 1 and 80.");
            if (creating) {
                requiredContact(record, "mobile_number", 32);
                requiredContact(record, "college_email", 254);
            }
            validateEmail(record.get("college_email"), "College email", creating);
            validateEmail(record.get("personal_email"), "Personal email", false);
            String mobile = Objects.toString(record.get("mobile_number"), "").trim();
            String digits = mobile.replaceAll("\\D", "");
            if (!mobile.isEmpty() && (!mobile.matches("\\+?[0-9() .-]+") || digits.length() < 7 || digits.length() > 15)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mobile number must contain 7 to 15 digits and may start with +.");
            }
            record.putIfAbsent("email", record.get("college_email"));
            record.putIfAbsent("active", true);
            record.putIfAbsent("qualified_subject_ids", List.of());
            record.putIfAbsent("unavailable_slot_ids", List.of());
            record.putIfAbsent("preferred_slot_ids", List.of());
        }
        if ("departments".equals(collection)) {
            Object blockNo = record.get("block_no");
            if (!(blockNo instanceof String value) || !value.matches("[0-9]{2}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department block number must be two digits, for example 01.");
            }
            Map<String, Object> departments = database.readMap(database.tenantPath(principal.institutionId(), "departments"));
            boolean duplicate = departments.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(record.get("id"))
                    && blockNo.equals(blockNumber(asMap(entry.getValue()).get("block_no"))));
            if (duplicate) throw new ResponseStatusException(HttpStatus.CONFLICT, "That department block number is already assigned.");
        }
        for (String key : List.of("qualified_subject_ids", "unavailable_slot_ids", "preferred_slot_ids", "equipment")) {
            Object value = record.get(key);
            if (value != null && !(value instanceof Collection<?>)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be an array.");
            }
        }
    }

    private void ensureDepartmentScope(AppPrincipal principal, String collection, Map<String, Object> existing) {
        if ("DEPARTMENT_ADMIN".equals(principal.role()) && !"departments".equals(collection)
                && principal.departmentId() != null
                && !principal.departmentId().equals(existing.get("department_id"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Department administrators can only edit records in their department.");
        }
    }

    private void requiredText(Map<String, Object> record, String field) {
        if (!(record.get(field) instanceof String value) || value.isBlank() || value.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required.");
        }
    }

    private void requiredContact(Map<String, Object> record, String field, int maxLength) {
        if (!(record.get(field) instanceof String value) || value.isBlank() || value.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required.");
        }
    }

    private void validateEmail(Object value, String label, boolean required) {
        String email = Objects.toString(value, "").trim();
        if (email.isEmpty() && !required) return;
        if (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be a valid email address.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String blockNumber(Object value) {
        String blockNo = Objects.toString(value, "");
        return blockNo.matches("[0-9]") ? "0" + blockNo : blockNo;
    }

    private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> map)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each scheduling input row must be an object.");
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    private void validateShiftSlot(Map<String, Object> slot, Map<String, Map<String, Object>> shiftsById) {
        LocalTime start = parseTime(slot.get("start_time"), "Time slot start_time");
        LocalTime end = parseTime(slot.get("end_time"), "Time slot end_time");
        if (!start.isBefore(end) || start.plusHours(1).compareTo(end) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each timetable slot must be exactly one hour.");
        }
        Object rawShiftIds = slot.get("shift_ids");
        if (!(rawShiftIds instanceof Collection<?> shiftIds) || shiftIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every slot must belong to at least one configured shift.");
        }
        Set<String> actualShiftIds = new HashSet<>();
        for (Object rawId : shiftIds) actualShiftIds.add(Objects.toString(rawId, ""));
        if (actualShiftIds.size() != shiftIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A slot must not list the same shift more than once.");
        }
        Set<String> expectedShiftIds = new HashSet<>();
        for (Map<String, Object> shift : shiftsById.values()) {
            LocalTime shiftStart = parseTime(shift.get("start_time"), "Shift start time");
            LocalTime shiftEnd = parseTime(shift.get("end_time"), "Shift end time");
            if (!start.isBefore(shiftStart) && !end.isAfter(shiftEnd)) expectedShiftIds.add(Objects.toString(shift.get("id")));
        }
        if (!actualShiftIds.equals(expectedShiftIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Time slots must be shared by every shift whose hours contain that slot.");
        }
        for (Object rawId : shiftIds) {
            String shiftId = Objects.toString(rawId, "");
            Map<String, Object> shift = shiftsById.get(shiftId);
            if (shift == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A time slot references an unknown shift.");
            LocalTime shiftStart = parseTime(shift.get("start_time"), "Shift start time");
            LocalTime shiftEnd = parseTime(shift.get("end_time"), "Shift end time");
            if (start.isBefore(shiftStart) || end.isAfter(shiftEnd)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A time slot falls outside its assigned shift.");
            }
        }
    }

    private LocalTime parseTime(Object value, String label) {
        try {
            if (!(value instanceof String text)) throw new IllegalArgumentException();
            return LocalTime.parse(text);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must use HH:mm format.");
        }
    }

    private void uniqueIds(List<Map<String, Object>> rows, String label) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> row : rows) {
            requiredText(row, "id");
            if (!ids.add(Objects.toString(row.get("id")))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " contains a duplicate id.");
        }
    }

    private void validKey(String id) {
        try { database.tenantPath("validation", id); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Record id must contain only letters, numbers, underscores, or hyphens."); }
    }

    private void audit(AppPrincipal principal, String action, String collection, String id) {
        String key = UUID.randomUUID().toString().replace("-", "");
        database.write(database.tenantPath(principal.institutionId(), "auditLogs", key), Map.of(
                "actorUid", principal.uid(), "action", action, "collection", collection,
                "entityId", id, "createdAt", Instant.now().toString()));
    }

    private Object filterInputRows(Object value, String departmentId, boolean includeAll, boolean includeShared) {
        if (value instanceof List<?> rows) {
            return rows.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .filter(row -> includeAll || departmentId.equals(row.get("department_id"))
                            || includeShared && row.get("department_id") == null).toList();
        }
        if (value instanceof Map<?, ?> rows) {
            Map<String, Object> scoped = new LinkedHashMap<>();
            rows.forEach((key, row) -> {
                if (!(row instanceof Map<?, ?> record)) return;
                if (includeAll || departmentId.equals(record.get("department_id"))
                        || includeShared && record.get("department_id") == null) scoped.put(Objects.toString(key), row);
            });
            return scoped;
        }
        return value;
    }
}
