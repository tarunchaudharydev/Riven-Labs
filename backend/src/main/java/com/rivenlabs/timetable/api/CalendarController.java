package com.rivenlabs.timetable.api;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import com.rivenlabs.timetable.service.TimetableDirectoryEnricher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {
    private static final ZoneId COLLEGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter UTC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));
    private static final Set<String> ALLOWED_ROLES = Set.of(
            "SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "FACULTY", "ACADEMIC_APPROVER", "STUDENT_VIEWER");

    private final FirebaseAuth auth;
    private final RealtimeDatabaseStore database;
    private final TimetableDirectoryEnricher directory;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String configuredPublicBaseUrl;

    public CalendarController(FirebaseAuth auth, RealtimeDatabaseStore database,
                              TimetableDirectoryEnricher directory,
                              @Value("${app.calendar.public-base-url:}") String configuredPublicBaseUrl) {
        this.auth = auth;
        this.database = database;
        this.directory = directory;
        this.configuredPublicBaseUrl = configuredPublicBaseUrl;
    }

    @GetMapping("/feeds")
    public List<Map<String, Object>> feeds(@AuthenticationPrincipal AppPrincipal principal,
                                           HttpServletRequest request) {
        String userKey = sha256(principal.uid());
        Map<String, Object> stored = database.readMap(database.tenantPath(
                principal.institutionId(), "calendarFeedLinks", userKey));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> item : stored.entrySet()) {
            Map<String, Object> row = asMap(item.getValue());
            if (!principal.uid().equals(row.get("uid"))) continue;
            String token = Objects.toString(row.get("token"), "");
            if (token.isBlank()) continue;
            String url = feedUrl(request, principal.institutionId(), userKey, token);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", item.getKey());
            view.put("name", Objects.toString(row.get("name"), "My timetable"));
            view.put("createdAt", Objects.toString(row.get("createdAt"), ""));
            view.put("url", url);
            view.put("webcalUrl", url.replaceFirst("^https?://", "webcal://"));
            result.add(view);
        }
        return result;
    }

    @PostMapping("/feeds")
    public Map<String, Object> createFeed(@AuthenticationPrincipal AppPrincipal principal,
                                          HttpServletRequest request) {
        if (!ALLOWED_ROLES.contains(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A timetable role is required to create a calendar subscription.");
        }
        String userKey = sha256(principal.uid());
        String parent = database.tenantPath(principal.institutionId(), "calendarFeedLinks", userKey);
        Map<String, Object> existing = database.readMap(parent);
        if (existing.size() >= 10) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You can have up to 10 calendar subscriptions. Revoke one before adding another.");
        }
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String feedId = sha256(token);
        String createdAt = Instant.now().toString();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("uid", principal.uid());
        record.put("institutionId", principal.institutionId());
        record.put("token", token);
        record.put("roleAtCreation", principal.role());
        record.put("name", "GLA timetable");
        record.put("createdAt", createdAt);
        database.write(database.tenantPath(principal.institutionId(), "calendarFeedLinks", userKey, feedId), record);
        String url = feedUrl(request, principal.institutionId(), userKey, token);
        return Map.of("id", feedId, "name", "GLA timetable", "createdAt", createdAt,
                "url", url, "webcalUrl", url.replaceFirst("^https?://", "webcal://"));
    }

    @DeleteMapping("/feeds/{feedId}")
    public Map<String, Object> revokeFeed(@AuthenticationPrincipal AppPrincipal principal,
                                          @PathVariable String feedId) {
        if (!feedId.matches("[a-f0-9]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Calendar subscription ID is invalid.");
        }
        String userKey = sha256(principal.uid());
        String path = database.tenantPath(principal.institutionId(), "calendarFeedLinks", userKey, feedId);
        Map<String, Object> record = database.readMap(path);
        if (record.isEmpty() || !principal.uid().equals(record.get("uid"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar subscription was not found.");
        }
        database.write(path, null);
        return Map.of("status", "REVOKED", "id", feedId);
    }

    @GetMapping(value = "/feeds/{institutionId}/{userKey}/{token}.ics", produces = "text/calendar;charset=UTF-8")
    public ResponseEntity<String> calendar(@PathVariable String institutionId,
                                           @PathVariable String userKey,
                                           @PathVariable String token) {
        if (!userKey.matches("[a-f0-9]{64}") || !token.matches("[A-Za-z0-9_-]{40,64}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar feed was not found.");
        }
        String feedId = sha256(token);
        Map<String, Object> feed = database.readMap(database.tenantPath(
                institutionId, "calendarFeedLinks", userKey, feedId));
        String savedToken = Objects.toString(feed.get("token"), "");
        String uid = Objects.toString(feed.get("uid"), "");
        if (savedToken.isBlank() || uid.isBlank() || !constantTimeEquals(savedToken, token)
                || !sha256(uid).equals(userKey) || !institutionId.equals(feed.get("institutionId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar feed was not found or has been revoked.");
        }
        Map<String, Object> claims;
        try {
            UserRecord user = auth.getUser(uid);
            claims = user.getCustomClaims() == null ? Map.of() : user.getCustomClaims();
        } catch (FirebaseAuthException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar feed owner is no longer available.");
        }
        String role = Objects.toString(claims.get("role"), "");
        if (!ALLOWED_ROLES.contains(role) || !institutionId.equals(claims.get("institutionId"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The account no longer has timetable access.");
        }
        String body = buildCalendar(institutionId, claims);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache, no-store, must-revalidate")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private String buildCalendar(String institutionId, Map<String, Object> claims) {
        String role = Objects.toString(claims.get("role"), "");
        String sectionId = Objects.toString(claims.get("sectionId"), "");
        String facultyId = Objects.toString(claims.get("facultyId"), "");
        String departmentId = Objects.toString(claims.get("departmentId"), "");
        String root = database.tenantPath(institutionId);
        Map<String, Object> academicTerms = database.readMap(root + "/academicTerms");
        Map<String, Object> publishedTerms = database.readMap(root + "/publishedTimetables");
        List<String> lines = new ArrayList<>(List.of(
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//GLA University//Academic Timetable//EN",
                "CALSCALE:GREGORIAN", "METHOD:PUBLISH", "X-WR-CALNAME:GLA University Timetable",
                "X-WR-TIMEZONE:Asia/Kolkata"));
        String now = UTC_DATE_TIME.format(Instant.now());
        for (Map.Entry<String, Object> publishedTerm : publishedTerms.entrySet()) {
            String termId = publishedTerm.getKey();
            Map<String, Object> active = asMap(asMap(publishedTerm.getValue()).get("active"));
            String draftId = Objects.toString(active.get("draftId"), "");
            if (draftId.isBlank()) continue;
            Map<String, Object> term = asMap(academicTerms.get(termId));
            Map<String, Object> version = database.readMap(database.tenantPath(
                    institutionId, "publishedTimetables", termId, "versions", draftId));
            List<Map<String, Object>> enriched = directory.enrich(
                    institutionId, termId, maps(version.get("entries")), true);
            for (Map<String, Object> entry : enriched) {
                if (!visible(entry, role, sectionId, facultyId, departmentId)) continue;
                LocalTime startTime = parseTime(entry.get("time_start"));
                LocalTime endTime = parseTime(entry.get("time_end"));
                DayOfWeek day = dayOfWeek(entry.get("day"));
                if (startTime == null || endTime == null || !endTime.isAfter(startTime) || day == null) continue;
                LocalDate startDate = date(term, "start_date", "startDate", LocalDate.now(COLLEGE_ZONE));
                LocalDate endDate = date(term, "end_date", "endDate", startDate.plusMonths(6));
                if (endDate.isBefore(startDate)) continue;
                LocalDate firstClass = startDate.with(TemporalAdjusters.nextOrSame(day));
                if (firstClass.isAfter(endDate)) continue;
                Instant start = LocalDateTime.of(firstClass, startTime).atZone(COLLEGE_ZONE).toInstant();
                Instant end = LocalDateTime.of(firstClass, endTime).atZone(COLLEGE_ZONE).toInstant();
                Instant until = LocalDateTime.of(endDate, LocalTime.of(23, 59, 59)).atZone(COLLEGE_ZONE).toInstant();
                String section = Objects.toString(entry.get("section_name"), Objects.toString(entry.get("section_id"), ""));
                String subject = Objects.toString(entry.get("subject_name"), Objects.toString(entry.get("subject_id"), "Class"));
                String room = room(entry);
                String cabin = Objects.toString(entry.get("faculty_cabin_no"), "");
                String faculty = Objects.toString(entry.get("faculty_name"), Objects.toString(entry.get("faculty_id"), ""));
                String department = Objects.toString(entry.get("department_name"), Objects.toString(entry.get("department_code"), ""));
                String location = room.isBlank() ? "GLA University" : room;
                String description = "Section: " + section + "\nFaculty: " + faculty
                        + (cabin.isBlank() ? "" : "\nFaculty cabin: " + cabin)
                        + (department.isBlank() ? "" : "\nDepartment: " + department)
                        + "\nAcademic term: " + termId;
                String uid = sha256(institutionId + ":" + termId + ":" + Objects.toString(entry.get("requirement_id"), "")
                        + ":" + Objects.toString(entry.get("section_id"), "") + ":" + day + ":" + entry.get("period")) + "@gla-timetable.edu";
                lines.addAll(List.of(
                        "BEGIN:VEVENT", "UID:" + uid, "DTSTAMP:" + now,
                        "DTSTART:" + UTC_DATE_TIME.format(start), "DTEND:" + UTC_DATE_TIME.format(end),
                        "RRULE:FREQ=WEEKLY;BYDAY=" + day.name().substring(0, 2) + ";UNTIL=" + UTC_DATE_TIME.format(until),
                        "LAST-MODIFIED:" + now, "SEQUENCE:0",
                        "SUMMARY:" + escape(subject + " · " + section), "LOCATION:" + escape(location),
                        "DESCRIPTION:" + escape(description), "STATUS:CONFIRMED", "TRANSP:OPAQUE", "END:VEVENT"));
            }
        }
        lines.add("END:VCALENDAR");
        StringBuilder output = new StringBuilder();
        for (String line : lines) output.append(fold(line)).append("\r\n");
        return output.toString();
    }

    private boolean visible(Map<String, Object> entry, String role, String sectionId,
                            String facultyId, String departmentId) {
        if ("STUDENT_VIEWER".equals(role)) return sectionId.equals(entry.get("section_id"));
        if ("FACULTY".equals(role)) return facultyId.equals(entry.get("faculty_id"));
        if ("DEPARTMENT_ADMIN".equals(role)) return !departmentId.isBlank() && departmentId.equals(entry.get("department_id"));
        return true;
    }

    private String feedUrl(HttpServletRequest request, String institutionId, String userKey, String token) {
        String base = configuredPublicBaseUrl == null || configuredPublicBaseUrl.isBlank()
                ? requestBaseUrl(request) : configuredPublicBaseUrl.trim().replaceFirst("/+$", "");
        return base + "/api/v1/calendar/feeds/" + institutionId + "/" + userKey + "/" + token + ".ics";
    }

    private String requestBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String server = request.getServerName();
        int port = request.getServerPort();
        boolean standardPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme + "://" + server + (standardPort ? "" : ":" + port);
    }

    private String room(Map<String, Object> entry) {
        List<String> parts = new ArrayList<>();
        String building = Objects.toString(entry.get("room_building"), "");
        String code = Objects.toString(entry.get("room_code"), Objects.toString(entry.get("room_name"), ""));
        if (!building.isBlank()) parts.add(building);
        if (!code.isBlank()) parts.add(code);
        return String.join(" ", parts);
    }

    private LocalDate date(Map<String, Object> term, String snake, String camel, LocalDate fallback) {
        Object raw = term.getOrDefault(snake, term.get(camel));
        if (raw == null) return fallback;
        try { return LocalDate.parse(Objects.toString(raw).substring(0, 10)); }
        catch (DateTimeParseException | IndexOutOfBoundsException ignored) { return fallback; }
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

    private DayOfWeek dayOfWeek(Object raw) {
        String value = Objects.toString(raw, "").trim().toUpperCase(Locale.ROOT);
        if (value.length() >= 3) value = value.substring(0, 3);
        return switch (value) {
            case "MON" -> DayOfWeek.MONDAY; case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY; case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY; case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY; default -> null;
        };
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    private String fold(String line) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < line.length();) {
            int codePoint = line.codePointAt(offset);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + unitBytes > 73) {
                result.append("\r\n ");
                bytes = 1;
            }
            result.append(unit);
            bytes += unitBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String input) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not create a calendar subscription key.", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<Map<String, Object>> rows = new ArrayList<>();
            map.forEach((key, raw) -> {
                if (raw instanceof Map<?, ?> item) {
                    Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) item);
                    row.putIfAbsent("id", Objects.toString(key));
                    rows.add(row);
                }
            });
            return rows;
        }
        if (value instanceof List<?> list) return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        return List.of();
    }
}
