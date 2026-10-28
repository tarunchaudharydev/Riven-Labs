package com.rivenlabs.timetable.api;

import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final RealtimeDatabaseStore database;
    public DashboardController(RealtimeDatabaseStore database) { this.database = database; }

    @GetMapping
    public Map<String, Object> dashboard(@AuthenticationPrincipal AppPrincipal principal) {
        String root = database.tenantPath(principal.institutionId());
        Map<String, Object> profile = database.readMap(root + "/profile");
        Map<String, Object> terms = database.readMap(root + "/academicTerms");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("institutionName", profile.getOrDefault("name", "Your institution"));
        data.put("role", principal.role());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("departments", database.readMap(root + "/departments").size());
        counts.put("faculty", database.readMap(root + "/faculty").size());
        counts.put("subjects", database.readMap(root + "/subjects").size());
        counts.put("sections", database.readMap(root + "/sections").size());
        counts.put("rooms", database.readMap(root + "/rooms").size());
        counts.put("terms", terms.size());
        if (isReviewer(principal.role())) counts.put("drafts", database.readMap(root + "/timetableDrafts").size());
        else counts.put("drafts", 0);
        data.put("counts", counts);
        data.put("terms", terms);
        data.put("publishedTimetables", published(root, principal));
        data.put("drafts", drafts(root, principal));
        return data;
    }

    private List<Map<String, Object>> drafts(String root, AppPrincipal principal) {
        if (!isReviewer(principal.role())) return List.of();
        Map<String, Object> stored = database.readMap(root + "/timetableDrafts");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> item : stored.entrySet()) {
            Map<String, Object> draft = asMap(item.getValue());
            if (!List.of("DRAFT", "APPROVED").contains(draft.get("status"))) continue;
            if ("TIMETABLE_INCHARGE".equals(principal.role()) && !principal.uid().equals(draft.get("generatedBy"))) continue;
            if ("DEPARTMENT_ADMIN".equals(principal.role()) && !principal.uid().equals(draft.get("generatedBy"))
                    && !Objects.equals(principal.departmentId(), draft.get("departmentId"))) continue;
            Map<String, Object> copy = new LinkedHashMap<>(draft);
            copy.putIfAbsent("id", item.getKey());
            result.add(copy);
        }
        result.sort((a, b) -> Objects.toString(b.get("createdAt"), "").compareTo(Objects.toString(a.get("createdAt"), "")));
        return result.stream().limit(25).toList();
    }

    private List<Map<String, Object>> published(String root, AppPrincipal principal) {
        Map<String, Object> terms = database.readMap(root + "/publishedTimetables");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> item : terms.entrySet()) {
            Map<String, Object> term = asMap(item.getValue());
            Map<String, Object> pointer = asMap(term.get("active"));
            String draftId = Objects.toString(pointer.get("draftId"), "");
            if (draftId.isBlank()) continue;
            Map<String, Object> draft = database.readMap(database.tenantPath(principal.institutionId(), "publishedTimetables", item.getKey(), "versions", draftId));
            List<Map<String, Object>> entries = withSubstitutions(root, item.getKey(), maps(draft.get("entries"))).stream().filter(entry -> {
                if ("STUDENT_VIEWER".equals(principal.role())) return principal.sectionId().equals(entry.get("section_id"));
                if ("FACULTY".equals(principal.role())) return principal.facultyId().equals(entry.get("faculty_id"));
                if ("DEPARTMENT_ADMIN".equals(principal.role()) && principal.departmentId() != null) return principal.departmentId().equals(entry.get("department_id"));
                return true;
            }).toList();
            result.add(Map.of("termId", item.getKey(), "draftId", draftId,
                    "publishedAt", Objects.toString(pointer.getOrDefault("publishedAt", "")), "entries", entries));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (value instanceof Map<?, ?> map) return map.values().stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        if (value instanceof List<?> list) return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        return List.of();
    }
    private boolean isReviewer(String role) { return role.equals("SUPER_ADMIN") || role.equals("DEPARTMENT_ADMIN") || role.equals("TIMETABLE_INCHARGE") || role.equals("ACADEMIC_APPROVER"); }

    private List<Map<String, Object>> withSubstitutions(String root, String termId, List<Map<String, Object>> entries) {
        Map<String, Object> overlays = database.readMap(root + "/activeSubstitutions/" + termId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Map<String, Object> copy = new LinkedHashMap<>(entry);
            String key = Objects.toString(copy.get("requirement_id"), "") + "_"
                    + Objects.toString(copy.get("day"), "").toUpperCase(java.util.Locale.ROOT) + "_"
                    + Objects.toString(copy.get("period"), "");
            Object raw = overlays.get(key);
            if (raw instanceof Map<?, ?> overlay) {
                copy.put("faculty_id", overlay.get("substituteFacultyId"));
                copy.put("faculty_name", overlay.get("substituteFacultyName"));
                copy.put("substitute", true);
            }
            result.add(copy);
        }
        return result;
    }
}
