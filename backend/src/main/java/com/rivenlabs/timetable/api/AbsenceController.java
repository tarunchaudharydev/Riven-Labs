package com.rivenlabs.timetable.api;

import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/absences")
public class AbsenceController {
    private static final Set<String> MANAGERS = Set.of("SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER");
    private final RealtimeDatabaseStore database;

    public AbsenceController(RealtimeDatabaseStore database) { this.database = database; }

    @PostMapping
    public Map<String, Object> report(@AuthenticationPrincipal AppPrincipal principal,
                                      @Valid @RequestBody AbsenceRequest request) {
        if (!MANAGERS.contains(principal.role()) && !"FACULTY".equals(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account cannot report faculty absences.");
        }
        String tenant = principal.institutionId();
        Map<String, Object> pointer = database.readMap(database.tenantPath(tenant, "publishedTimetables", request.termId(), "active"));
        String draftId = Objects.toString(pointer.get("draftId"), "");
        if (draftId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No published timetable exists for this term.");
        Map<String, Object> published = database.readMap(database.tenantPath(tenant, "publishedTimetables", request.termId(), "versions", draftId));
        List<Map<String, Object>> entries = maps(published.get("entries"));
        Map<String, Object> affected = entries.stream().filter(entry -> request.requirementId().equals(entry.get("requirement_id"))
                && request.day().equalsIgnoreCase(Objects.toString(entry.get("day"), ""))
                && number(entry.get("period")) == request.period()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No published class matches that day and period."));
        String facultyId = Objects.toString(affected.get("faculty_id"), "");
        if ("FACULTY".equals(principal.role()) && !Objects.equals(principal.facultyId(), facultyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Faculty members can report absences only for their own classes.");
        }
        if ("DEPARTMENT_ADMIN".equals(principal.role()) && !Objects.equals(principal.departmentId(), affected.get("department_id"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only report absences in your department.");
        }
        String assignmentKey = assignmentKey(request.requirementId(), request.day(), request.period());
        Map<String, Object> replacement = database.readMap(database.tenantPath(tenant, "activeSubstitutions", request.termId(), assignmentKey));
        if (!replacement.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This class already has an approved substitute.");
        boolean alreadyReported = database.readMap(database.tenantPath(tenant, "absences")).values().stream()
                .filter(Map.class::isInstance).map(value -> (Map<?, ?>) value)
                .anyMatch(item -> request.termId().equals(item.get("termId"))
                        && request.requirementId().equals(item.get("requirementId"))
                        && request.day().equalsIgnoreCase(Objects.toString(item.get("day"), ""))
                        && number(item.get("period")) == request.period()
                        && "PENDING_COVER".equals(item.get("status")));
        if (alreadyReported) throw new ResponseStatusException(HttpStatus.CONFLICT, "An absence is already waiting for cover for this class.");
        String absenceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> absence = new LinkedHashMap<>();
        absence.put("id", absenceId); absence.put("termId", request.termId()); absence.put("requirementId", request.requirementId());
        absence.put("facultyId", facultyId); absence.put("facultyName", affected.getOrDefault("faculty_name", facultyId));
        absence.put("sectionId", affected.get("section_id")); absence.put("departmentId", affected.get("department_id"));
        absence.put("day", request.day().toUpperCase(Locale.ROOT)); absence.put("period", request.period());
        absence.put("roomId", affected.get("room_id")); absence.put("reason", request.reason().trim());
        absence.put("status", "PENDING_COVER"); absence.put("reportedBy", principal.uid()); absence.put("reportedAt", Instant.now().toString());
        database.write(database.tenantPath(tenant, "absences", absenceId), absence);
        audit(principal, "FACULTY_ABSENCE_REPORTED", absenceId);
        return absence;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AppPrincipal principal,
                                          @RequestParam(required = false) String termId) {
        if (!MANAGERS.contains(principal.role()) && !"FACULTY".equals(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account cannot view faculty absences.");
        }
        Map<String, Object> stored = database.readMap(database.tenantPath(principal.institutionId(), "absences"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : stored.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> absence = new LinkedHashMap<>((Map<String, Object>) raw);
            absence.putIfAbsent("id", entry.getKey());
            if (termId != null && !termId.equals(absence.get("termId"))) continue;
            if ("FACULTY".equals(principal.role()) && !Objects.equals(principal.facultyId(), absence.get("facultyId"))) continue;
            if ("DEPARTMENT_ADMIN".equals(principal.role()) && !Objects.equals(principal.departmentId(), absence.get("departmentId"))) continue;
            result.add(absence);
        }
        result.sort(Comparator.comparing(item -> Objects.toString(item.get("reportedAt"), ""), Comparator.reverseOrder()));
        return result.stream().limit(100).toList();
    }

    static String assignmentKey(String requirementId, String day, int period) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(requirementId.getBytes(StandardCharsets.UTF_8));
            StringBuilder shortHash = new StringBuilder("a_");
            for (int i = 0; i < 12; i++) shortHash.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return shortHash + "_" + day.toUpperCase(Locale.ROOT) + "_" + period;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (value instanceof Map<?, ?> map) return map.values().stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        if (value instanceof List<?> list) return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        return List.of();
    }

    private void audit(AppPrincipal principal, String action, String entityId) {
        String id = UUID.randomUUID().toString().replace("-", "");
        database.write(database.tenantPath(principal.institutionId(), "auditLogs", id), Map.of(
                "actorUid", principal.uid(), "action", action, "entityId", entityId, "createdAt", Instant.now().toString()));
    }

    public record AbsenceRequest(@NotBlank @Size(max = 128) String termId,
                                 @NotBlank @Size(max = 128) String requirementId,
                                 @NotBlank @Size(max = 3) @Pattern(regexp = "(?i)MON|TUE|WED|THU|FRI|SAT|SUN") String day,
                                 @Min(1) @Max(24) int period,
                                 @NotBlank @Size(max = 500) String reason) { }
}
