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
        return database.readMap(database.tenantPath(principal.institutionId(), "schedulingInputs", termId));
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
        for (Map<String, Object> item : slots) {
            requiredText(item, "id"); requiredText(item, "day");
            if (number(item.get("period")) < 1 || !Boolean.TRUE.equals(item.getOrDefault("is_teaching_period", true))) {
                if (number(item.get("period")) < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every teaching slot needs a positive period number.");
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
        if (!"DEPARTMENT_ADMIN".equals(principal.role()) || "departments".equals(collection)) return values;
        Map<String, Object> scoped = new LinkedHashMap<>();
        values.forEach((id, value) -> {
            if (value instanceof Map<?, ?> item && principal.departmentId().equals(item.get("department_id"))) scoped.put(id, value);
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
        validateRecord(principal, collection, record);
        String recordPath = database.tenantPath(principal.institutionId(), collection, id);
        Map<String, Object> previous = database.readMap(recordPath);
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

    private void validateRecord(AppPrincipal principal, String collection, Map<String, Object> record) {
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
        if ("rooms".equals(collection)) {
            int capacity = number(record.get("capacity"));
            if (capacity < 1 || capacity > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room capacity must be between 1 and 10000.");
            requiredText(record, "room_type");
        }
        if ("faculty".equals(collection)) {
            int maxPeriods = number(record.get("max_weekly_periods"));
            if (maxPeriods < 1 || maxPeriods > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Weekly faculty periods must be between 1 and 80.");
            record.putIfAbsent("active", true);
            record.putIfAbsent("qualified_subject_ids", List.of());
            record.putIfAbsent("unavailable_slot_ids", List.of());
            record.putIfAbsent("preferred_slot_ids", List.of());
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
}
