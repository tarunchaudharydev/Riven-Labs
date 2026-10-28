package com.rivenlabs.timetable.api;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import com.rivenlabs.timetable.service.TimetableDirectoryEnricher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/student")
public class StudentPortalController {
    private final FirebaseAuth auth;
    private final RealtimeDatabaseStore database;
    private final TimetableDirectoryEnricher directory;

    public StudentPortalController(FirebaseAuth auth, RealtimeDatabaseStore database,
                                   TimetableDirectoryEnricher directory) {
        this.auth = auth;
        this.database = database;
        this.directory = directory;
    }

    @GetMapping("/portal")
    public Map<String, Object> portal(@AuthenticationPrincipal AppPrincipal principal) {
        if (!"STUDENT_VIEWER".equals(principal.role()) || principal.sectionId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Student access is required for this portal.");
        }
        String institutionId = principal.institutionId();
        String root = database.tenantPath(institutionId);
        Map<String, Object> section = database.readMap(database.tenantPath(institutionId, "sections", principal.sectionId()));
        Map<String, Object> profile = profile(principal, section);
        Map<String, Object> termRecords = database.readMap(root + "/academicTerms");
        Map<String, Object> publishedTerms = database.readMap(root + "/publishedTimetables");
        Map<String, Object> subjectRecords = database.readMap(root + "/subjects");
        Map<String, Object> syllabusRecords = database.readMap(root + "/syllabi");
        Set<String> sectionSubjectIds = new LinkedHashSet<>();
        List<Map<String, Object>> terms = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();

        for (Map.Entry<String, Object> termRow : termRecords.entrySet()) {
            String termId = termRow.getKey();
            Map<String, Object> term = new LinkedHashMap<>(asMap(termRow.getValue()));
            term.put("id", termId);
            terms.add(term);

            Map<String, Object> active = asMap(asMap(publishedTerms.get(termId)).get("active"));
            String draftId = Objects.toString(active.get("draftId"), "");
            if (draftId.isBlank()) continue;
            Map<String, Object> published = database.readMap(database.tenantPath(
                    institutionId, "publishedTimetables", termId, "versions", draftId));
            for (Map<String, Object> entry : directory.enrich(institutionId, termId, maps(published.get("entries")), true)) {
                if (!principal.sectionId().equals(entry.get("section_id"))) continue;
                Map<String, Object> copy = new LinkedHashMap<>(entry);
                copy.put("termId", termId);
                copy.put("publishedAt", Objects.toString(active.get("publishedAt"), ""));
                entries.add(copy);
                String subjectId = Objects.toString(entry.get("subject_id"), "");
                if (!subjectId.isBlank()) sectionSubjectIds.add(subjectId);
            }
            Map<String, Object> schedulingInput = database.readMap(database.tenantPath(institutionId, "schedulingInputs", termId));
            for (Map<String, Object> requirement : maps(schedulingInput.get("requirements"))) {
                if (principal.sectionId().equals(requirement.get("section_id"))) {
                    String subjectId = Objects.toString(requirement.get("subject_id"), "");
                    if (!subjectId.isBlank()) sectionSubjectIds.add(subjectId);
                }
            }
        }
        sectionSubjectIds.addAll(strings(section.get("subject_ids")));
        sectionSubjectIds.addAll(strings(section.get("subjects")));
        List<Map<String, Object>> subjects = sectionSubjectIds.stream().map(id -> subject(id, subjectRecords, syllabusRecords))
                .filter(subject -> !subject.isEmpty()).toList();
        entries.sort((a, b) -> {
            int term = Objects.toString(a.get("termId"), "").compareTo(Objects.toString(b.get("termId"), ""));
            if (term != 0) return term;
            int day = Integer.compare(dayNumber(a.get("day")), dayNumber(b.get("day")));
            return day != 0 ? day : Integer.compare(integer(a.get("period")), integer(b.get("period")));
        });
        terms.sort((a, b) -> Objects.toString(a.get("start_date"), Objects.toString(a.get("name"), ""))
                .compareTo(Objects.toString(b.get("start_date"), Objects.toString(b.get("name"), ""))));
        return Map.of("profile", profile, "section", section, "terms", terms, "entries", entries, "subjects", subjects);
    }

    private Map<String, Object> profile(AppPrincipal principal, Map<String, Object> section) {
        try {
            UserRecord user = auth.getUser(principal.uid());
            Map<String, Object> claims = user.getCustomClaims();
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("uid", user.getUid());
            profile.put("displayName", Objects.toString(user.getDisplayName(), "Student"));
            profile.put("email", Objects.toString(user.getEmail(), ""));
            profile.put("photoUrl", Objects.toString(user.getPhotoUrl(), ""));
            profile.put("collegeEmail", Objects.toString(claims.get("studentCollegeEmail"), ""));
            profile.put("rollNo", Objects.toString(claims.get("studentRollNo"), ""));
            profile.put("sectionId", principal.sectionId());
            profile.put("sectionName", Objects.toString(section.getOrDefault("name", section.getOrDefault("code", principal.sectionId()))));
            profile.put("departmentId", Objects.toString(section.get("department_id"), ""));
            Map<String, Object> department = database.readMap(database.tenantPath(
                    principal.institutionId(), "departments", Objects.toString(section.get("department_id"), "_none")));
            profile.put("departmentName", Objects.toString(department.get("name"), ""));
            profile.put("departmentCode", Objects.toString(department.get("code"), ""));
            putIfPresent(profile, section, "semester", "semester");
            putIfPresent(profile, section, "program", "program");
            putIfPresent(profile, section, "batch", "batch");
            return profile;
        } catch (FirebaseAuthException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not load the student profile from Firebase Authentication.", ex);
        }
    }

    private Map<String, Object> subject(String id, Map<String, Object> subjectRecords,
                                        Map<String, Object> syllabusRecords) {
        Map<String, Object> record = new LinkedHashMap<>(asMap(subjectRecords.get(id)));
        if (record.isEmpty()) return Map.of();
        record.put("id", id);
        Map<String, Object> syllabus = asMap(syllabusRecords.get(id));
        if (!syllabus.isEmpty()) {
            for (String key : List.of("description", "syllabus", "units", "syllabusUrl", "syllabus_url", "learningOutcomes")) {
                if (syllabus.get(key) != null) record.put(key, syllabus.get(key));
            }
        }
        return record;
    }

    private void putIfPresent(Map<String, Object> target, Map<String, Object> source, String sourceKey, String targetKey) {
        if (source.get(sourceKey) != null) target.put(targetKey, source.get(sourceKey));
    }

    private int dayNumber(Object value) {
        return switch (Objects.toString(value, "").substring(0, Math.min(3, Objects.toString(value, "").length())).toUpperCase(Locale.ROOT)) {
            case "MON" -> 1; case "TUE" -> 2; case "WED" -> 3; case "THU" -> 4; case "FRI" -> 5; case "SAT" -> 6; case "SUN" -> 7; default -> 8;
        };
    }

    private int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(Objects.toString(value, "")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }

    private List<String> strings(Object value) {
        if (value instanceof List<?> list) return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        if (value instanceof Map<?, ?> map) return map.keySet().stream().map(Objects::toString).toList();
        return List.of();
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
