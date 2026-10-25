package com.rivenlabs.timetable.api;

import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import com.rivenlabs.timetable.security.RoleGuard;
import com.rivenlabs.timetable.service.AiServiceClient;
import com.rivenlabs.timetable.service.SchedulingInputService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/v1/substitutions")
public class SubstitutionController {
    private final RealtimeDatabaseStore database;
    private final SchedulingInputService inputs;
    private final AiServiceClient ai;
    private final RoleGuard roles;

    public SubstitutionController(RealtimeDatabaseStore database, SchedulingInputService inputs, AiServiceClient ai, RoleGuard roles) {
        this.database = database; this.inputs = inputs; this.ai = ai; this.roles = roles;
    }

    @PostMapping("/recommend")
    public Map<String, Object> recommend(@AuthenticationPrincipal AppPrincipal principal, @Valid @RequestBody RecommendationRequest request) {
        roles.require(principal, "SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER");
        Map<String, Object> problem = inputs.load(principal, request.termId());
        Map<String, Object> req = maps(problem.get("requirements")).stream()
                .filter(item -> request.requirementId().equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching requirement not found."));
        String tenant = principal.institutionId();
        Map<String, Object> activePointer = database.readMap(database.tenantPath(tenant, "publishedTimetables", request.termId(), "active"));
        String draftId = Objects.toString(activePointer.get("draftId"), "");
        if (draftId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No published timetable exists for this term.");
        Map<String, Object> published = database.readMap(database.tenantPath(tenant, "publishedTimetables", request.termId(), "versions", draftId));
        List<Map<String, Object>> entries = maps(published.get("entries"));
        Map<String, Object> affected = entries.stream().filter(entry -> request.requirementId().equals(entry.get("requirement_id"))
                && request.day().equals(entry.get("day")) && number(entry.get("period")) == request.period()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No published class matches that day and period."));
        String missingFacultyId = Objects.toString(affected.get("faculty_id"), "");
        List<String> slotIds = strings(affected.get("slot_ids"));
        Map<String, Map<String, Object>> members = byId(problem.get("faculty"));
        Map<String, Object> missingFaculty = members.getOrDefault(missingFacultyId, Map.of());
        String departmentId = Objects.toString(missingFaculty.get("department_id"), "");
        Set<String> authorizedFaculty = new HashSet<>(strings(req.get("faculty_ids")));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> member : members.values()) {
            String id = Objects.toString(member.get("id"), "");
            if (id.isBlank() || id.equals(missingFacultyId)) continue;
            boolean qualified = authorizedFaculty.contains(id) && strings(member.get("qualified_subject_ids")).contains(Objects.toString(req.get("subject_id"), ""));
            boolean available = Boolean.TRUE.equals(member.getOrDefault("active", true))
                    && Collections.disjoint(slotIds, strings(member.get("unavailable_slot_ids")))
                    && entries.stream().filter(entry -> id.equals(entry.get("faculty_id")))
                    .noneMatch(entry -> !Collections.disjoint(slotIds, strings(entry.get("slot_ids"))));
            int workload = entries.stream().filter(entry -> id.equals(entry.get("faculty_id")))
                    .mapToInt(entry -> number(entry.get("duration"))).sum();
            int maximum = Math.max(1, number(member.get("max_weekly_periods")));
            int sectionClasses = (int) entries.stream().filter(entry -> id.equals(entry.get("faculty_id"))
                    && Objects.equals(entry.get("section_id"), req.get("section_id"))).count();
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("id", id); candidate.put("name", member.getOrDefault("name", id));
            candidate.put("department_id", member.getOrDefault("department_id", ""));
            candidate.put("qualified", qualified); candidate.put("available", available);
            candidate.put("remaining_periods", Math.max(0, maximum - workload)); candidate.put("max_weekly_periods", maximum);
            candidate.put("section_familiarity", sectionClasses > 0 ? 100 : Math.min(100, number(member.get("section_familiarity_score"))));
            candidate.put("experience_score", Math.min(100, number(member.get("substitution_experience_score"))));
            candidates.add(candidate);
        }
        return ai.post("/api/v1/substitutions/recommend", Map.of("subject_id", req.get("subject_id"),
                "department_id", departmentId, "required_periods", number(affected.get("duration")), "candidates", candidates));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (value instanceof Map<?, ?> map) return map.values().stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        if (value instanceof List<?> list) return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        return List.of();
    }
    private Map<String, Map<String, Object>> byId(Object value) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> member : maps(value)) result.put(Objects.toString(member.get("id"), ""), member);
        return result;
    }
    private List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of(); }
    private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

    public record RecommendationRequest(@NotBlank @Size(max = 128) String termId,
                                        @NotBlank @Size(max = 128) String requirementId,
                                        @NotBlank @Size(max = 32) String day,
                                        @Min(1) @Max(24) int period) { }
}
