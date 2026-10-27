package com.rivenlabs.timetable.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class SchedulingInputService {
    private final RealtimeDatabaseStore database;
    private final ObjectMapper mapper;

    public SchedulingInputService(RealtimeDatabaseStore database, ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper;
    }

    public Map<String, Object> load(AppPrincipal principal, String termId) {
        String path = database.tenantPath(principal.institutionId(), "schedulingInputs", termId);
        Map<String, Object> input = database.readMap(path);
        if (input.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No approved scheduling input exists for this academic term.");
        for (String collection : List.of("time_slots", "sections", "faculty", "rooms", "requirements")) {
            input.put(collection, normalize(input.get(collection)));
        }
        if ("DEPARTMENT_ADMIN".equals(principal.role())) input = scopeToDepartment(principal, input);
        input.put("term_id", termId);
        return input;
    }

    public Map<String, Object> catalog(Map<String, Object> input) {
        List<Map<String, Object>> subjects = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> requirement : maps(input.get("requirements"))) {
            String id = string(requirement.get("subject_id"));
            if (id != null && seen.add(id)) subjects.add(Map.of("id", id, "name", Objects.toString(requirement.get("subject_name"), id)));
        }
        return Map.of(
                "sections", entities(input.get("sections"), true),
                "subjects", subjects,
                "faculty", entities(input.get("faculty"), true),
                "rooms", entities(input.get("rooms"), false),
                "time_slots", entities(input.get("time_slots"), false));
    }

    public Map<String, Object> withApprovedChanges(Map<String, Object> input, Map<String, Object> interpretation) {
        Map<String, Object> copy = mapper.convertValue(input, new TypeReference<>() {});
        List<Map<String, Object>> requirements = maps(copy.get("requirements"));
        for (Map<String, Object> change : maps(interpretation.get("changes"))) {
            String subjectId = string(change.get("subject_id"));
            String sectionId = string(change.get("section_id"));
            Map<String, Object> target = requirements.stream().filter(req -> Objects.equals(subjectId, string(req.get("subject_id")))
                    && Objects.equals(sectionId, string(req.get("section_id")))).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "An interpreted requirement no longer matches the approved term data."));
            setIfPresent(target, change, "sessions_per_week");
            setIfPresent(target, change, "periods_per_session");
            mergeValues(target, change, "preferred_faculty_ids");
            mergeValues(target, change, "excluded_slot_ids");
            mergeValues(target, change, "preferred_slot_ids");
        }
        return copy;
    }

    private void setIfPresent(Map<String, Object> target, Map<String, Object> change, String key) {
        if (change.get(key) != null) target.put(key, change.get(key));
    }

    private void mergeValues(Map<String, Object> target, Map<String, Object> change, String key) {
        if (!(change.get(key) instanceof List<?> additions) || additions.isEmpty()) return;
        LinkedHashSet<Object> merged = new LinkedHashSet<>();
        if (target.get(key) instanceof List<?> current) merged.addAll(current);
        merged.addAll(additions);
        target.put(key, new ArrayList<>(merged));
    }

    private Map<String, Object> scopeToDepartment(AppPrincipal principal, Map<String, Object> input) {
        if (principal.departmentId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Department administrator account is missing its department assignment.");
        Map<String, Object> scoped = mapper.convertValue(input, new TypeReference<>() {});
        String departmentId = principal.departmentId();
        List<Map<String, Object>> requirements = maps(scoped.get("requirements")).stream()
                .filter(item -> departmentId.equals(item.get("department_id"))).toList();
        List<Map<String, Object>> sections = maps(scoped.get("sections")).stream()
                .filter(item -> departmentId.equals(item.get("department_id"))).toList();
        List<String> sectionIds = sections.stream().map(item -> Objects.toString(item.get("id"), "")).toList();
        requirements = requirements.stream().filter(req -> sectionIds.contains(Objects.toString(req.get("section_id"), ""))).toList();
        if (requirements.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No scheduling requirements are assigned to your department.");
        List<Map<String, Object>> faculty = maps(scoped.get("faculty")).stream()
                .filter(item -> departmentId.equals(item.get("department_id"))).toList();
        List<String> facultyIds = faculty.stream().map(item -> Objects.toString(item.get("id"), "")).toList();
        for (Map<String, Object> req : requirements) {
            if (!facultyIds.containsAll(listStrings(req.get("faculty_ids")))) {
                req.put("faculty_ids", listStrings(req.get("faculty_ids")).stream().filter(facultyIds::contains).toList());
            }
        }
        List<Map<String, Object>> rooms = maps(scoped.get("rooms")).stream()
                .filter(item -> item.get("department_id") == null || departmentId.equals(item.get("department_id"))).toList();
        scoped.put("requirements", requirements);
        scoped.put("sections", sections);
        scoped.put("faculty", faculty);
        scoped.put("rooms", rooms);
        return scoped;
    }

    private List<String> listStrings(Object value) { return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of(); }

    private List<Map<String, Object>> entities(Object raw, boolean includeDepartment) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : maps(raw)) {
            Map<String, Object> entity = new LinkedHashMap<>();
            if (item.get("id") != null) entity.put("id", item.get("id"));
            if (item.get("name") != null) entity.put("name", item.get("name"));
            if (includeDepartment && item.get("department_id") != null) entity.put("department_id", item.get("department_id"));
            if (item.get("day") != null) { entity.put("day", item.get("day")); entity.put("period", item.get("period")); }
            if (entity.get("id") != null && entity.get("name") != null || entity.get("day") != null) result.add(entity);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (value instanceof Map<?, ?> map) return map.values().stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        if (value instanceof List<?> list) return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalize(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> item) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) item);
                    copy.putIfAbsent("id", Objects.toString(entry.getKey()));
                    result.add(copy);
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> map) result.add(new LinkedHashMap<>((Map<String, Object>) map));
        }
        return result;
    }

    private String string(Object value) { return value instanceof String text ? text : null; }
}
