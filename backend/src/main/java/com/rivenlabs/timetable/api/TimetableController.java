package com.rivenlabs.timetable.api;

import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import com.rivenlabs.timetable.security.RoleGuard;
import com.rivenlabs.timetable.service.SchedulingInputService;
import com.rivenlabs.timetable.service.TimetableDirectoryEnricher;
import com.rivenlabs.timetable.service.TimetableGenerationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class TimetableController {
    private final RealtimeDatabaseStore database;
    private final SchedulingInputService inputs;
    private final TimetableGenerationService generation;
    private final TimetableDirectoryEnricher directory;
    private final RoleGuard roles;

    public TimetableController(RealtimeDatabaseStore database, SchedulingInputService inputs,
                               TimetableGenerationService generation, TimetableDirectoryEnricher directory,
                               RoleGuard roles) {
        this.database = database; this.inputs = inputs; this.generation = generation;
        this.directory = directory; this.roles = roles;
    }

    @PostMapping("/timetables/generate")
    public Map<String, Object> generate(@AuthenticationPrincipal AppPrincipal principal, @Valid @RequestBody GenerateRequest request) {
        roles.require(principal, "SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE");
        String tenant = principal.institutionId();
        Map<String, Object> interpretation = database.readMap(database.tenantPath(tenant, "interpretedRequirements", request.interpretationId()));
        if (interpretation.isEmpty() || !"PENDING_REVIEW".equals(interpretation.get("status"))
                || !request.termId().equals(interpretation.get("termId"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This interpretation is no longer pending review for the selected term.");
        }
        if ("DEPARTMENT_ADMIN".equals(principal.role())
                && !Objects.equals(principal.departmentId(), interpretation.get("departmentId"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only generate a timetable from an interpretation for your department.");
        }
        if (!principal.uid().equals(interpretation.get("createdBy")) && !SetHolder.ADMIN_ROLES.contains(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the interpreter or a department administrator can apply this review.");
        }
        if (!asList(interpretation.get("clarification_questions")).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Resolve the AI clarification questions before generating a timetable.");
        }
        Map<String, Object> problem = inputs.withApprovedChanges(inputs.load(principal, request.termId()), interpretation);
        String jobId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", jobId); job.put("termId", request.termId()); job.put("status", "QUEUED");
        job.put("createdBy", principal.uid()); job.put("createdAt", Instant.now().toString());
        job.put("interpretationId", request.interpretationId());
        if (principal.departmentId() != null) job.put("departmentId", principal.departmentId());
        database.write(database.tenantPath(tenant, "generationJobs", jobId), job);
        database.update(database.tenantPath(tenant, "interpretedRequirements", request.interpretationId()), Map.of("status", "APPLIED"));
        generation.run(tenant, principal.uid(), principal.departmentId(), jobId, request.interpretationId(), request.termId(), problem);
        return Map.of("jobId", jobId, "status", "QUEUED");
    }

    @GetMapping("/timetable-jobs/{jobId}")
    public Map<String, Object> job(@AuthenticationPrincipal AppPrincipal principal, @PathVariable String jobId) {
        Map<String, Object> result = database.readMap(database.tenantPath(principal.institutionId(), "generationJobs", jobId));
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation job not found.");
        if ("DEPARTMENT_ADMIN".equals(principal.role())
                && !Objects.equals(principal.departmentId(), result.get("departmentId"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot read a timetable job from another department.");
        }
        if (!principal.uid().equals(result.get("createdBy")) && !SetHolder.ADMIN_ROLES.contains(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot read this generation job.");
        }
        return result;
    }

    @GetMapping("/timetables/drafts/{draftId}")
    public Map<String, Object> draft(@AuthenticationPrincipal AppPrincipal principal, @PathVariable String draftId) {
        Map<String, Object> result = database.readMap(database.tenantPath(principal.institutionId(), "timetableDrafts", draftId));
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Timetable draft not found.");
        boolean reviewer = "SUPER_ADMIN".equals(principal.role()) || "ACADEMIC_APPROVER".equals(principal.role());
        boolean ownDepartment = "DEPARTMENT_ADMIN".equals(principal.role()) && Objects.equals(principal.departmentId(), result.get("departmentId"));
        if ("DEPARTMENT_ADMIN".equals(principal.role()) && !ownDepartment) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view timetable drafts for your department.");
        }
        if (!principal.uid().equals(result.get("generatedBy")) && !reviewer && !ownDepartment) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this timetable draft.");
        }
        return result;
    }

    @PostMapping("/timetables/{draftId}/approve")
    public Map<String, Object> approve(@AuthenticationPrincipal AppPrincipal principal, @PathVariable String draftId) {
        roles.require(principal, "ACADEMIC_APPROVER", "SUPER_ADMIN");
        String path = database.tenantPath(principal.institutionId(), "timetableDrafts", draftId);
        Map<String, Object> draft = database.readMap(path);
        requireState(draft, "DRAFT");
        database.update(path, Map.of("status", "APPROVED", "approvedBy", principal.uid(), "approvedAt", Instant.now().toString()));
        audit(principal, "TIMETABLE_APPROVED", draftId);
        return Map.of("draftId", draftId, "status", "APPROVED");
    }

    @PostMapping("/timetables/{draftId}/publish")
    public Map<String, Object> publish(@AuthenticationPrincipal AppPrincipal principal, @PathVariable String draftId) {
        roles.require(principal, "SUPER_ADMIN");
        String root = database.tenantPath(principal.institutionId());
        String draftPath = database.tenantPath(principal.institutionId(), "timetableDrafts", draftId);
        Map<String, Object> draft = database.readMap(draftPath);
        requireState(draft, "APPROVED");
        String termId = Objects.toString(draft.get("termId"), "");
        if (termId.isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Approved draft is missing its term id.");
        Map<String, Object> snapshot = new LinkedHashMap<>(draft);
        snapshot.put("status", "PUBLISHED"); snapshot.put("publishedBy", principal.uid()); snapshot.put("publishedAt", Instant.now().toString());
        if (draft.get("departmentId") instanceof String departmentId && !departmentId.isBlank()) {
            Map<String, Object> active = database.readMap(database.tenantPath(principal.institutionId(), "publishedTimetables", termId, "active"));
            String activeDraftId = Objects.toString(active.get("draftId"), "");
            if (!activeDraftId.isBlank()) {
                Map<String, Object> previous = database.readMap(database.tenantPath(principal.institutionId(), "publishedTimetables", termId, "versions", activeDraftId));
                List<Map<String, Object>> mergedEntries = new ArrayList<>();
                mergedEntries.addAll(maps(previous.get("entries")).stream().filter(entry -> !departmentId.equals(entry.get("department_id"))).toList());
                mergedEntries.addAll(maps(draft.get("entries")));
                snapshot.put("entries", mergedEntries);
            }
        }
        List<Map<String, Object>> nextEntries = directory.enrich(
                principal.institutionId(), termId, maps(snapshot.get("entries")), false);
        snapshot.put("entries", nextEntries);
        Map<String, Object> changes = new LinkedHashMap<>();
        String publishedPath = database.tenantPath(principal.institutionId(), "publishedTimetables", termId).substring(root.length() + 1);
        changes.put(publishedPath + "/versions/" + draftId, snapshot);
        changes.put(publishedPath + "/active", Map.of("draftId", draftId, "publishedAt", snapshot.get("publishedAt")));
        Map<String, Object> previousLive = database.readMap(database.tenantPath(principal.institutionId(), "liveTimetableSnapshots", termId));
        changes.put("liveTimetableSnapshots/" + termId, liveSnapshot(snapshot, nextEntries));
        addLiveAudienceCopies(changes, termId, snapshot, nextEntries, maps(previousLive.get("entries")));
        changes.put("activeSubstitutions/" + termId, null);
        changes.put("timetableDrafts/" + draftId + "/status", "PUBLISHED");
        changes.put("timetableDrafts/" + draftId + "/publishedBy", principal.uid());
        changes.put("timetableDrafts/" + draftId + "/publishedAt", snapshot.get("publishedAt"));
        database.update(root, changes);
        audit(principal, "TIMETABLE_PUBLISHED", draftId);
        return Map.of("draftId", draftId, "status", "PUBLISHED");
    }

    private void requireState(Map<String, Object> draft, String expected) {
        if (draft.isEmpty() || !expected.equals(draft.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "The timetable is not in the required lifecycle state.");
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (value instanceof Map<?, ?> map) return map.values().stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        if (value instanceof List<?> list) return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        return List.of();
    }
    private java.util.List<?> asList(Object value) { return value instanceof java.util.List<?> list ? list : java.util.List.of(); }
    private void audit(AppPrincipal principal, String action, String entityId) {
        String key = UUID.randomUUID().toString().replace("-", "");
        database.write(database.tenantPath(principal.institutionId(), "auditLogs", key),
                Map.of("actorUid", principal.uid(), "action", action, "entityId", entityId, "createdAt", Instant.now().toString()));
    }
    private Map<String, Object> liveSnapshot(Map<String, Object> snapshot, List<Map<String, Object>> entries) {
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("draftId", snapshot.get("id"));
        live.put("termId", snapshot.get("termId"));
        live.put("publishedAt", snapshot.get("publishedAt"));
        live.put("solverStatus", snapshot.get("solverStatus"));
        live.put("entries", entries);
        return live;
    }
    private void addLiveAudienceCopies(Map<String, Object> changes, String termId,
                                       Map<String, Object> snapshot, List<Map<String, Object>> entries,
                                       List<Map<String, Object>> previousEntries) {
        Map<String, List<Map<String, Object>>> bySection = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> byFaculty = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> byDepartment = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            addAudience(bySection, entry, "section_id");
            addAudience(byFaculty, entry, "faculty_id");
            addAudience(byDepartment, entry, "department_id");
        }
        bySection.forEach((id, values) -> changes.put("liveTimetables/" + termId + "/sections/" + id, liveSnapshot(snapshot, values)));
        byFaculty.forEach((id, values) -> changes.put("liveTimetables/" + termId + "/faculty/" + id, liveSnapshot(snapshot, values)));
        byDepartment.forEach((id, values) -> changes.put("liveTimetables/" + termId + "/departments/" + id, liveSnapshot(snapshot, values)));
        removeStaleAudienceCopies(changes, termId, "sections", bySection.keySet(), previousEntries, "section_id");
        removeStaleAudienceCopies(changes, termId, "faculty", byFaculty.keySet(), previousEntries, "faculty_id");
        removeStaleAudienceCopies(changes, termId, "departments", byDepartment.keySet(), previousEntries, "department_id");
    }
    private void removeStaleAudienceCopies(Map<String, Object> changes, String termId, String audience,
                                           Set<String> currentIds, List<Map<String, Object>> previousEntries,
                                           String field) {
        for (Map<String, Object> entry : previousEntries) {
            String id = Objects.toString(entry.get(field), "");
            if (!id.isBlank() && !currentIds.contains(id)) changes.put("liveTimetables/" + termId + "/" + audience + "/" + id, null);
        }
    }
    private void addAudience(Map<String, List<Map<String, Object>>> grouped,
                             Map<String, Object> entry, String key) {
        String id = Objects.toString(entry.get(key), "");
        if (!id.isBlank()) grouped.computeIfAbsent(id, ignored -> new ArrayList<>()).add(entry);
    }
    public record GenerateRequest(@NotBlank @Size(max = 128) String termId, @NotBlank @Size(max = 128) String interpretationId) {}
    private static class SetHolder {
        static final java.util.Set<String> ADMIN_ROLES = java.util.Set.of("SUPER_ADMIN", "DEPARTMENT_ADMIN");
        static final java.util.Set<String> REVIEW_ROLES = java.util.Set.of("SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER");
    }
}
