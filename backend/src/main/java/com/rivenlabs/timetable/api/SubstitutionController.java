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

import java.time.Instant;
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
        roles.require(principal, "SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER", "FACULTY");
        Map<String, Object> problem = inputs.load(principal, request.termId());
        Map<String, Object> req = maps(problem.get("requirements")).stream()
                .filter(item -> request.requirementId().equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching requirement not found."));
        String tenant = principal.institutionId();
        Map<String, Object> activePointer = database.readMap(database.tenantPath(tenant, "publishedTimetables", request.termId(), "active"));
        String draftId = Objects.toString(activePointer.get("draftId"), "");
        if (draftId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No published timetable exists for this term.");
        Map<String, Object> published = database.readMap(database.tenantPath(tenant, "publishedTimetables", request.termId(), "versions", draftId));
        List<Map<String, Object>> entries = applySubstitutionOverlays(tenant, request.termId(), maps(published.get("entries")));
        Map<String, Object> affected = entries.stream().filter(entry -> request.requirementId().equals(entry.get("requirement_id"))
                && request.day().equals(entry.get("day")) && number(entry.get("period")) == request.period()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No published class matches that day and period."));
        String missingFacultyId = Objects.toString(affected.get("faculty_id"), "");
        if ("FACULTY".equals(principal.role()) && !Objects.equals(principal.facultyId(), missingFacultyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Faculty members can request cover only for their own classes.");
        }
        if ("DEPARTMENT_ADMIN".equals(principal.role()) && !Objects.equals(principal.departmentId(), affected.get("department_id"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only request cover in your department.");
        }
        List<String> slotIds = strings(affected.get("slot_ids"));
        Map<String, Map<String, Object>> members = byId(problem.get("faculty"));
        String departmentId = Objects.toString(affected.get("department_id"), "");
        Set<String> authorizedFaculty = new HashSet<>(strings(req.get("faculty_ids")));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> member : members.values()) {
            String id = Objects.toString(member.get("id"), "");
            if (id.isBlank() || id.equals(missingFacultyId)) continue;
            boolean qualified = authorizedFaculty.contains(id);
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
            candidate.put("department_name", member.getOrDefault("department_name", ""));
            candidate.put("department_code", member.getOrDefault("department_code", ""));
            candidate.put("department_block_no", member.getOrDefault("department_block_no", ""));
            candidate.put("qualified", qualified); candidate.put("available", available);
            candidate.put("remaining_periods", Math.max(0, maximum - workload)); candidate.put("max_weekly_periods", maximum);
            candidate.put("section_familiarity", sectionClasses > 0 ? 100 : Math.min(100, number(member.get("section_familiarity_score"))));
            candidate.put("experience_score", Math.min(100, number(member.get("substitution_experience_score"))));
            candidates.add(candidate);
        }
        return ai.post("/api/v1/substitutions/recommend", Map.of("subject_id", req.get("subject_id"),
                "department_id", departmentId, "required_periods", number(affected.get("duration")), "candidates", candidates));
    }

    @PostMapping("/assign")
    public Map<String, Object> assign(@AuthenticationPrincipal AppPrincipal principal,
                                      @Valid @RequestBody AssignmentRequest request) {
        roles.require(principal, "SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER");
        String tenant = principal.institutionId();
        String day = request.day().toUpperCase(Locale.ROOT);
        String assignmentKey = AbsenceController.assignmentKey(request.requirementId(), day, request.period());
        Map<String, Object> existing = database.readMap(database.tenantPath(tenant, "activeSubstitutions", request.termId(), assignmentKey));
        if (!existing.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This class already has an approved substitute.");

        Map<String, Object> problem = inputs.load(principal, request.termId());
        Map<String, Object> requirement = maps(problem.get("requirements")).stream()
                .filter(item -> request.requirementId().equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching requirement not found."));
        Map<String, Object> pointer = database.readMap(database.tenantPath(tenant, "publishedTimetables", request.termId(), "active"));
        String draftId = Objects.toString(pointer.get("draftId"), "");
        if (draftId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No published timetable exists for this term.");
        Map<String, Object> published = database.readMap(database.tenantPath(tenant, "publishedTimetables", request.termId(), "versions", draftId));
        List<Map<String, Object>> entries = applySubstitutionOverlays(tenant, request.termId(), maps(published.get("entries")));
        Map<String, Object> affected = entries.stream().filter(entry -> request.requirementId().equals(entry.get("requirement_id"))
                && day.equalsIgnoreCase(Objects.toString(entry.get("day"), "")) && number(entry.get("period")) == request.period())
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No published class matches that day and period."));
        if ("DEPARTMENT_ADMIN".equals(principal.role()) && !Objects.equals(principal.departmentId(), affected.get("department_id"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only assign substitutes in your department.");
        }
        String originalFacultyId = Objects.toString(affected.get("faculty_id"), "");
        Map<String, Map<String, Object>> members = byId(problem.get("faculty"));
        Map<String, Object> candidate = members.get(request.facultyId());
        if (candidate == null || request.facultyId().equals(originalFacultyId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Select a different faculty member from the approved faculty list.");
        }
        List<String> authorizedFaculty = strings(requirement.get("faculty_ids"));
        if (!authorizedFaculty.contains(request.facultyId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The selected faculty member is not authorized for this subject.");
        }
        List<String> slotIds = strings(affected.get("slot_ids"));
        if (!Boolean.TRUE.equals(candidate.getOrDefault("active", true))
                || !Collections.disjoint(slotIds, strings(candidate.get("unavailable_slot_ids")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected faculty member is inactive or unavailable for this class.");
        }
        boolean overlap = entries.stream().filter(entry -> request.facultyId().equals(entry.get("faculty_id")))
                .filter(entry -> !(request.requirementId().equals(entry.get("requirement_id"))
                        && day.equalsIgnoreCase(Objects.toString(entry.get("day"), ""))
                        && number(entry.get("period")) == request.period()))
                .anyMatch(entry -> !Collections.disjoint(slotIds, strings(entry.get("slot_ids"))));
        if (overlap) throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected faculty member is already teaching during this time.");
        int workload = entries.stream().filter(entry -> request.facultyId().equals(entry.get("faculty_id")))
                .mapToInt(entry -> number(entry.get("duration"))).sum();
        int maxPeriods = number(candidate.get("max_weekly_periods"));
        int duration = Math.max(1, number(affected.get("duration")));
        if (maxPeriods > 0 && workload + duration > maxPeriods) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The substitute would exceed their maximum weekly workload.");
        }
        Map.Entry<String, Map<String, Object>> pendingAbsence = findPendingAbsence(tenant, request, day);
        if (pendingAbsence == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Report the faculty absence before assigning a substitute.");

        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("termId", request.termId()); replacement.put("requirementId", request.requirementId());
        replacement.put("day", day); replacement.put("period", request.period()); replacement.put("slotIds", slotIds);
        replacement.put("originalFacultyId", originalFacultyId); replacement.put("substituteFacultyId", request.facultyId());
        replacement.put("substituteFacultyName", candidate.getOrDefault("name", request.facultyId()));
        replacement.put("assignedBy", principal.uid()); replacement.put("assignedAt", Instant.now().toString());
        replacement.put("absenceId", pendingAbsence.getKey()); replacement.put("status", "ASSIGNED");

        Map<String, Object> replacedEntry = new LinkedHashMap<>(affected);
        replacedEntry.put("faculty_id", request.facultyId()); replacedEntry.put("faculty_name", candidate.getOrDefault("name", request.facultyId()));
        replacedEntry.put("substitute", true);
        List<Map<String, Object>> liveEntries = replaceAssignment(entries, request, day, replacedEntry, false);
        Map<String, Object> liveSnapshot = new LinkedHashMap<>();
        liveSnapshot.put("draftId", published.get("id")); liveSnapshot.put("termId", request.termId());
        liveSnapshot.put("publishedAt", pointer.get("publishedAt")); liveSnapshot.put("solverStatus", published.get("solverStatus"));
        liveSnapshot.put("entries", liveEntries);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("activeSubstitutions/" + request.termId() + "/" + assignmentKey, replacement);
        changes.put("liveTimetableSnapshots/" + request.termId(), liveSnapshot);
        addLiveMirrorChange(changes, tenant, request.termId(), "sections", Objects.toString(affected.get("section_id"), ""), request, day, replacedEntry, false);
        addLiveMirrorChange(changes, tenant, request.termId(), "departments", Objects.toString(affected.get("department_id"), ""), request, day, replacedEntry, false);
        addLiveMirrorChange(changes, tenant, request.termId(), "faculty", originalFacultyId, request, day, replacedEntry, true);
        addLiveMirrorChange(changes, tenant, request.termId(), "faculty", request.facultyId(), request, day, replacedEntry, false);
        String absencePath = "absences/" + pendingAbsence.getKey();
        changes.put(absencePath + "/status", "COVERED"); changes.put(absencePath + "/substituteFacultyId", request.facultyId());
        changes.put(absencePath + "/substituteFacultyName", candidate.getOrDefault("name", request.facultyId()));
        changes.put(absencePath + "/coveredAt", replacement.get("assignedAt")); changes.put(absencePath + "/assignedBy", principal.uid());
        database.update(database.tenantPath(tenant), changes);
        audit(principal, "FACULTY_SUBSTITUTE_ASSIGNED", assignmentKey);
        return replacement;
    }

    private Map.Entry<String, Map<String, Object>> findPendingAbsence(String tenant, AssignmentRequest request, String day) {
        Map<String, Object> records = database.readMap(database.tenantPath(tenant, "absences"));
        for (Map.Entry<String, Object> item : records.entrySet()) {
            if (!(item.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> absence = castMap(raw);
            if (request.termId().equals(absence.get("termId")) && request.requirementId().equals(absence.get("requirementId"))
                    && day.equals(absence.get("day")) && number(absence.get("period")) == request.period()
                    && "PENDING_COVER".equals(absence.get("status"))) return Map.entry(item.getKey(), absence);
        }
        return null;
    }

    private List<Map<String, Object>> applySubstitutionOverlays(String tenant, String termId, List<Map<String, Object>> entries) {
        Map<String, Object> overlays = database.readMap(database.tenantPath(tenant, "activeSubstitutions", termId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Map<String, Object> copy = new LinkedHashMap<>(entry);
            String key = AbsenceController.assignmentKey(Objects.toString(copy.get("requirement_id"), ""), Objects.toString(copy.get("day"), ""), number(copy.get("period")));
            Object value = overlays.get(key);
            if (value instanceof Map<?, ?> raw) {
                Map<String, Object> overlay = castMap(raw);
                copy.put("faculty_id", overlay.get("substituteFacultyId")); copy.put("faculty_name", overlay.get("substituteFacultyName")); copy.put("substitute", true);
            }
            result.add(copy);
        }
        return result;
    }

    private List<Map<String, Object>> replaceAssignment(List<Map<String, Object>> source, AssignmentRequest request,
                                                       String day, Map<String, Object> replacement, boolean remove) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : source) {
            boolean target = request.requirementId().equals(item.get("requirement_id")) && day.equalsIgnoreCase(Objects.toString(item.get("day"), "")) && number(item.get("period")) == request.period();
            if (target && remove) continue;
            result.add(target ? replacement : item);
        }
        if (!remove && result.stream().noneMatch(item -> request.requirementId().equals(item.get("requirement_id")) && day.equalsIgnoreCase(Objects.toString(item.get("day"), "")) && number(item.get("period")) == request.period())) result.add(replacement);
        return result;
    }

    private void addLiveMirrorChange(Map<String, Object> changes, String tenant, String termId, String audience,
                                     String audienceId, AssignmentRequest request, String day,
                                     Map<String, Object> replacement, boolean remove) {
        if (audienceId.isBlank()) return;
        String relativePath = "liveTimetables/" + termId + "/" + audience + "/" + audienceId;
        Map<String, Object> mirror = database.readMap(database.tenantPath(tenant, "liveTimetables", termId, audience, audienceId));
        List<Map<String, Object>> oldEntries = maps(mirror.get("entries"));
        List<Map<String, Object>> newEntries = replaceAssignment(oldEntries, request, day, replacement, remove);
        if (mirror.isEmpty() && remove) return;
        Map<String, Object> updated = new LinkedHashMap<>(mirror);
        updated.put("entries", newEntries);
        changes.put(relativePath, updated);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> value) { return (Map<String, Object>) value; }

    private void audit(AppPrincipal principal, String action, String entityId) {
        String id = UUID.randomUUID().toString().replace("-", "");
        database.write(database.tenantPath(principal.institutionId(), "auditLogs", id), Map.of(
                "actorUid", principal.uid(), "action", action, "entityId", entityId, "createdAt", Instant.now().toString()));
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
    public record AssignmentRequest(@NotBlank @Size(max = 128) String termId,
                                   @NotBlank @Size(max = 128) String requirementId,
                                   @NotBlank @Size(max = 32) String day,
                                   @Min(1) @Max(24) int period,
                                   @NotBlank @Size(max = 128) String facultyId) { }
}
