package com.rivenlabs.timetable.api;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.ListUsersPage;
import com.google.firebase.auth.UserRecord;
import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import com.rivenlabs.timetable.service.TimetableDirectoryEnricher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {
    private static final ZoneId COLLEGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Set<String> MARKS = Set.of("PRESENT", "ABSENT", "LATE");

    private final FirebaseAuth auth;
    private final RealtimeDatabaseStore database;
    private final TimetableDirectoryEnricher directory;

    public AttendanceController(FirebaseAuth auth, RealtimeDatabaseStore database,
                                TimetableDirectoryEnricher directory) {
        this.auth = auth;
        this.database = database;
        this.directory = directory;
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions(@AuthenticationPrincipal AppPrincipal principal) {
        requireFaculty(principal);
        LocalDate date = LocalDate.now(COLLEGE_ZONE);
        String day = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
        String root = database.tenantPath(principal.institutionId());
        Map<String, Object> terms = database.readMap(root + "/publishedTimetables");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> termRow : terms.entrySet()) {
            String termId = termRow.getKey();
            Map<String, Object> active = asMap(asMap(termRow.getValue()).get("active"));
            String draftId = Objects.toString(active.get("draftId"), "");
            if (draftId.isBlank()) continue;
            Map<String, Object> published = database.readMap(database.tenantPath(
                    principal.institutionId(), "publishedTimetables", termId, "versions", draftId));
            List<Map<String, Object>> entries = directory.enrich(principal.institutionId(), termId,
                    maps(published.get("entries")), true);
            for (Map<String, Object> entry : entries) {
                if (!principal.facultyId().equals(entry.get("faculty_id")) || !day.equals(normalizeDay(entry.get("day")))) continue;
                String sectionId = Objects.toString(entry.get("section_id"), "");
                if (sectionId.isBlank()) continue;
                String key = sessionKey(termId, entry, date);
                Map<String, Object> attendance = database.readMap(database.tenantPath(
                        principal.institutionId(), "attendance", sectionId, date.toString(), key));
                Map<String, Object> storedMarks = asMap(attendance.get("records"));
                List<Map<String, Object>> students = studentsForSection(principal.institutionId(), sectionId, storedMarks);
                Map<String, Object> session = new LinkedHashMap<>(entry);
                session.put("termId", termId);
                session.put("date", date.toString());
                session.put("sessionKey", key);
                session.put("students", students);
                session.put("attendanceSavedAt", Objects.toString(attendance.get("syncedAt"), ""));
                result.add(session);
            }
        }
        result.sort(Comparator.comparing(value -> Objects.toString(value.get("time_start"), "")));
        return Map.of("date", date.toString(), "timezone", COLLEGE_ZONE.getId(), "sessions", result);
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(@AuthenticationPrincipal AppPrincipal principal,
                                    @Valid @RequestBody AttendanceSyncRequest request) {
        requireFaculty(principal);
        LocalDate date;
        Instant capturedAt;
        try {
            date = LocalDate.parse(request.date());
            capturedAt = Instant.parse(request.capturedAt());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attendance date or capture time is invalid.");
        }
        if (date.isAfter(LocalDate.now(COLLEGE_ZONE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attendance cannot be recorded for a future date.");
        }

        Map<String, Object> scheduled = matchingLecture(principal, request);
        validateCaptureWindow(scheduled, date, capturedAt);
        String sectionId = Objects.toString(scheduled.get("section_id"), "");
        Map<String, Map<String, Object>> roster = studentRoster(principal.institutionId(), sectionId);
        if (request.students().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attendance must include at least one student.");
        }
        Set<String> seen = new HashSet<>();
        String basePath = "attendance/" + sectionId + "/" + date + "/" + request.sessionKey();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put(basePath + "/termId", request.termId());
        changes.put(basePath + "/requirementId", request.requirementId());
        changes.put(basePath + "/sectionId", sectionId);
        changes.put(basePath + "/date", date.toString());
        changes.put(basePath + "/day", normalizeDay(scheduled.get("day")));
        changes.put(basePath + "/period", scheduled.get("period"));
        changes.put(basePath + "/subjectId", scheduled.get("subject_id"));
        changes.put(basePath + "/subjectName", scheduled.get("subject_name"));
        changes.put(basePath + "/facultyId", principal.facultyId());
        changes.put(basePath + "/capturedAt", capturedAt.toString());
        changes.put(basePath + "/syncedAt", Instant.now().toString());
        for (StudentMark mark : request.students()) {
            if (!MARKS.contains(mark.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attendance status must be PRESENT, ABSENT, or LATE.");
            }
            Map<String, Object> student = roster.get(mark.uid());
            if (student == null || !seen.add(mark.uid())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Attendance includes a duplicate or student outside this section.");
            }
            Map<String, Object> record = new LinkedHashMap<>(student);
            record.put("uid", mark.uid());
            record.put("status", mark.status());
            record.put("capturedAt", capturedAt.toString());
            record.put("markedBy", principal.uid());
            changes.put(basePath + "/records/" + userKey(mark.uid()), record);
        }
        database.update(database.tenantPath(principal.institutionId()), changes);
        return Map.of("status", "SYNCED", "sessionKey", request.sessionKey(), "syncedAt", Instant.now().toString(), "recordsSaved", request.students().size());
    }

    private Map<String, Object> matchingLecture(AppPrincipal principal, AttendanceSyncRequest request) {
        if (!request.sessionKey().equals(expectedSessionKey(request.termId(), request.requirementId(), request.day(), request.period(), request.date()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attendance session identity does not match its schedule.");
        }
        Map<String, Object> pointer = database.readMap(database.tenantPath(
                principal.institutionId(), "publishedTimetables", request.termId(), "active"));
        String draftId = Objects.toString(pointer.get("draftId"), "");
        if (draftId.isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is no published timetable for this session.");
        Map<String, Object> published = database.readMap(database.tenantPath(
                principal.institutionId(), "publishedTimetables", request.termId(), "versions", draftId));
        return directory.enrich(principal.institutionId(), request.termId(), maps(published.get("entries")), true).stream()
                .filter(entry -> request.requirementId().equals(entry.get("requirement_id")))
                .filter(entry -> request.sectionId().equals(entry.get("section_id")))
                .filter(entry -> principal.facultyId().equals(entry.get("faculty_id")))
                .filter(entry -> request.day().equalsIgnoreCase(normalizeDay(entry.get("day"))))
                .filter(entry -> request.period() == integer(entry.get("period")))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You can only take attendance for a section assigned to your faculty account."));
    }

    private void validateCaptureWindow(Map<String, Object> entry, LocalDate date, Instant capturedAt) {
        if (!normalizeDay(entry.get("day")).equals(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The scheduled lecture does not run on the attendance date.");
        }
        LocalTime start = parseTime(entry.get("time_start"));
        LocalTime end = parseTime(entry.get("time_end"));
        if (start == null || end == null || !end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "This timetable entry has no valid lecture start and end times.");
        }
        LocalDateTime capturedLocal = LocalDateTime.ofInstant(capturedAt, COLLEGE_ZONE);
        if (!capturedLocal.toLocalDate().equals(date)
                || capturedLocal.toLocalTime().isBefore(start)
                || capturedLocal.toLocalTime().isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Attendance can only be captured during the scheduled lecture time.");
        }
    }

    private LocalTime parseTime(Object raw) {
        String value = Objects.toString(raw, "").trim();
        if (value.isBlank()) return null;
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_TIME,
                DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH))) {
            try { return LocalTime.parse(value, formatter); }
            catch (DateTimeParseException ignored) { }
        }
        return null;
    }

    private List<Map<String, Object>> studentsForSection(String institutionId, String sectionId,
                                                          Map<String, Object> storedRecords) {
        Map<String, Map<String, Object>> roster = studentRoster(institutionId, sectionId);
        for (Map<String, Object> student : roster.values()) {
            String uid = Objects.toString(student.get("uid"), "");
            Map<String, Object> saved = asMap(storedRecords.get(userKey(uid)));
            if (MARKS.contains(saved.get("status"))) student.put("status", saved.get("status"));
        }
        return roster.values().stream().sorted(Comparator.comparing(value -> Objects.toString(value.get("rollNo"), ""))).toList();
    }

    private Map<String, Map<String, Object>> studentRoster(String institutionId, String sectionId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        try {
            ListUsersPage page = auth.listUsers(null, 1000);
            for (UserRecord user : page.iterateAll()) {
                Map<String, Object> claims = user.getCustomClaims();
                if (!"STUDENT_VIEWER".equals(claims.get("role"))
                        || !institutionId.equals(claims.get("institutionId"))
                        || !sectionId.equals(claims.get("sectionId"))) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uid", user.getUid());
                row.put("name", Objects.toString(user.getDisplayName(), "Student"));
                row.put("collegeEmail", Objects.toString(claims.get("studentCollegeEmail"), Objects.toString(user.getEmail(), "")));
                row.put("rollNo", Objects.toString(claims.get("studentRollNo"), ""));
                result.put(user.getUid(), row);
            }
        } catch (FirebaseAuthException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not load the student roster from Firebase Authentication.", ex);
        }
        return result;
    }

    private String sessionKey(String termId, Map<String, Object> entry, LocalDate date) {
        return expectedSessionKey(termId, Objects.toString(entry.get("requirement_id"), ""),
                normalizeDay(entry.get("day")), integer(entry.get("period")), date.toString());
    }

    private String expectedSessionKey(String termId, String requirementId, String day, int period, String date) {
        return UUID.nameUUIDFromBytes((termId + ":" + requirementId + ":" + day + ":" + period + ":" + date).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    private String userKey(String uid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uid.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) { throw new IllegalStateException("Could not create attendance record key.", ex); }
    }

    private void requireFaculty(AppPrincipal principal) {
        if (!"FACULTY".equals(principal.role()) || principal.facultyId() == null || principal.facultyId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Faculty access is required for attendance.");
        }
    }

    private String normalizeDay(Object value) {
        String day = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
        return day.length() > 3 ? day.substring(0, 3) : day;
    }

    private int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(Objects.toString(value, "")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (value instanceof Map<?, ?> map) return map.values().stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        if (value instanceof List<?> list) return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
        return List.of();
    }

    public record StudentMark(@NotBlank @Size(max = 128) String uid,
                              @NotBlank @Pattern(regexp = "PRESENT|ABSENT|LATE") String status) { }

    public record AttendanceSyncRequest(@NotBlank @Size(max = 128) String termId,
                                        @NotBlank @Size(max = 128) String requirementId,
                                        @NotBlank @Size(max = 128) String sectionId,
                                        @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String sessionKey,
                                        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String date,
                                        @NotBlank @Pattern(regexp = "MON|TUE|WED|THU|FRI|SAT|SUN") String day,
                                        @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(24) int period,
                                        @NotBlank String capturedAt,
                                        @Size(min = 1, max = 500) List<@Valid StudentMark> students) { }
}
