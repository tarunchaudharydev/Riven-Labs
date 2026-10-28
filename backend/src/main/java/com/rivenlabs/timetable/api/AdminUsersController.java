package com.rivenlabs.timetable.api;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.ListUsersPage;
import com.google.firebase.auth.UserRecord;
import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import com.rivenlabs.timetable.security.RoleGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUsersController {
    private static final Set<String> MANAGEABLE_ROLES = Set.of(
            "STUDENT_VIEWER", "FACULTY", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE",
            "ACADEMIC_APPROVER", "SUPER_ADMIN");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final FirebaseAuth firebaseAuth;
    private final RealtimeDatabaseStore database;
    private final RoleGuard roleGuard;

    public AdminUsersController(FirebaseAuth firebaseAuth, RealtimeDatabaseStore database, RoleGuard roleGuard) {
        this.firebaseAuth = firebaseAuth;
        this.database = database;
        this.roleGuard = roleGuard;
    }

    @GetMapping
    public Map<String, Object> users(
            @AuthenticationPrincipal AppPrincipal principal,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false) String search) {
        roleGuard.require(principal, "SUPER_ADMIN");
        int pageSize = Math.max(1, Math.min(limit, 100));
        try {
            Map<String, Object> facultyRecords = database.readMap(
                    database.tenantPath(principal.institutionId(), "faculty"));
            Map<String, Object> sectionRecords = database.readMap(
                    database.tenantPath(principal.institutionId(), "sections"));
            Map<String, Object> departmentRecords = database.readMap(
                    database.tenantPath(principal.institutionId(), "departments"));
            List<UserRecord> sourceUsers = new ArrayList<>();
            String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
            String nextPageToken;
            if (query.isEmpty()) {
                ListUsersPage page = firebaseAuth.listUsers(pageToken, pageSize);
                page.getValues().forEach(sourceUsers::add);
                nextPageToken = page.getNextPageToken();
            } else {
                List<UserRecord> matchingUsers = new ArrayList<>();
                for (UserRecord candidate : firebaseAuth.listUsers(null, 1000).iterateAll()) {
                    Map<String, Object> claims = candidate.getCustomClaims();
                    String institutionId = claimString(claims, "institutionId");
                    if (institutionId != null && !principal.institutionId().equals(institutionId)) continue;
                    String searchable = String.join(" ",
                            Objects.toString(candidate.getDisplayName(), ""),
                            Objects.toString(candidate.getEmail(), ""),
                            candidate.getUid(),
                            Objects.toString(claimString(claims, "studentCollegeEmail"), ""),
                            Objects.toString(facultyCollegeEmail(facultyRecords, claimString(claims, "facultyId")), ""));
                    if (searchable.toLowerCase(Locale.ROOT).contains(query)) matchingUsers.add(candidate);
                }
                int offset;
                try {
                    offset = pageToken == null || pageToken.isBlank() ? 0 : Integer.parseInt(pageToken);
                } catch (NumberFormatException ex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search result page.");
                }
                if (offset < 0 || offset > matchingUsers.size()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search result page.");
                }
                matchingUsers.sort(Comparator
                        .comparingInt((UserRecord candidate) -> Objects.toString(candidate.getEmail(), "").equalsIgnoreCase(query) ? 0
                                : Objects.toString(candidate.getEmail(), "").toLowerCase(Locale.ROOT).startsWith(query) ? 1 : 2)
                        .thenComparing(candidate -> Objects.toString(candidate.getDisplayName(), ""), String.CASE_INSENSITIVE_ORDER));
                int end = Math.min(offset + pageSize, matchingUsers.size());
                sourceUsers.addAll(matchingUsers.subList(offset, end));
                nextPageToken = end < matchingUsers.size() ? Integer.toString(end) : null;
            }
            List<Map<String, Object>> users = new ArrayList<>();
            for (UserRecord user : sourceUsers) {
                Map<String, Object> claims = user.getCustomClaims();
                String institutionId = claimString(claims, "institutionId");
                if (institutionId != null && !principal.institutionId().equals(institutionId)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uid", user.getUid());
                row.put("email", user.getEmail() == null ? "" : user.getEmail());
                row.put("displayName", user.getDisplayName() == null ? "" : user.getDisplayName());
                row.put("photoUrl", user.getPhotoUrl() == null ? "" : user.getPhotoUrl());
                row.put("providers", java.util.Arrays.stream(user.getProviderData()).map(provider -> provider.getProviderId()).toList());
                row.put("disabled", user.isDisabled());
                row.put("role", claimString(claims, "role"));
                row.put("institutionId", institutionId);
                row.put("departmentId", claimString(claims, "departmentId"));
                row.put("facultyId", facultyCollegeEmail(facultyRecords, claimString(claims, "facultyId")));
                String studentCollegeEmail = claimString(claims, "studentCollegeEmail");
                row.put("studentId", Objects.toString(studentCollegeEmail, ""));
                row.put("studentCollegeEmail", studentCollegeEmail);
                row.put("studentRollNo", claimString(claims, "studentRollNo"));
                row.put("sectionId", claimString(claims, "sectionId"));
                users.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("users", users);
            result.put("facultyOptions", facultyOptions(facultyRecords, departmentRecords));
            result.put("sectionOptions", sectionOptions(sectionRecords, departmentRecords));
            result.put("nextPageToken", nextPageToken);
            return result;
        } catch (FirebaseAuthException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not load Firebase Authentication users.", ex);
        }
    }

    @PutMapping("/{uid}/role")
    public Map<String, Object> assignRole(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable String uid,
            @Valid @RequestBody RoleAssignment request) {
        roleGuard.require(principal, "SUPER_ADMIN");
        String role = request.role() == null ? "" : request.role().trim().toUpperCase(Locale.ROOT);
        if (!role.isEmpty() && !MANAGEABLE_ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a supported timetable role.");
        }

        String departmentId = optionalId(request.departmentId(), "departmentId");
        String facultyId = "FACULTY".equals(role)
                ? resolveFacultyId(principal.institutionId(), request.facultyId()) : null;
        String sectionId = optionalId(request.sectionId(), "sectionId");
        String studentCollegeEmail = "STUDENT_VIEWER".equals(role)
                ? optionalEmail(request.studentCollegeEmail(), "Student college email") : null;
        String studentRollNo = "STUDENT_VIEWER".equals(role)
                ? optionalStudentRollNo(request.studentRollNo()) : null;
        switch (role) {
            case "STUDENT_VIEWER" -> { departmentId = null; facultyId = null; }
            case "FACULTY" -> { sectionId = null; studentRollNo = null; }
            case "DEPARTMENT_ADMIN" -> { facultyId = null; sectionId = null; studentRollNo = null; }
            case "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER" -> { facultyId = null; sectionId = null; studentRollNo = null; }
            case "SUPER_ADMIN", "" -> { departmentId = null; facultyId = null; sectionId = null; studentRollNo = null; }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a supported timetable role.");
        }
        if ("DEPARTMENT_ADMIN".equals(role) && departmentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department Admin requires a department assignment.");
        }
        if ("FACULTY".equals(role) && facultyId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Teacher requires a faculty college email assignment.");
        }
        if ("STUDENT_VIEWER".equals(role) && sectionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Student requires a section assignment.");
        }
        if ("STUDENT_VIEWER".equals(role) && studentCollegeEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Student requires a valid college email ID.");
        }

        verifyRecord(principal.institutionId(), "departments", departmentId, "Department");
        verifyRecord(principal.institutionId(), "faculty", facultyId, "Faculty member");
        verifyRecord(principal.institutionId(), "sections", sectionId, "Section");

        try {
            UserRecord target = firebaseAuth.getUser(uid);
            String targetInstitution = claimString(target.getCustomClaims(), "institutionId");
            if (targetInstitution != null && !principal.institutionId().equals(targetInstitution)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That user does not belong to this institution.");
            }
            if ("SUPER_ADMIN".equals(target.getCustomClaims().get("role")) && !"SUPER_ADMIN".equals(role)
                    && isOnlySuperAdmin(principal.institutionId(), uid)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Assign another Super Admin before changing this account.");
            }

            Map<String, Object> claims = new LinkedHashMap<>(target.getCustomClaims());
            for (String key : List.of("institutionId", "role", "departmentId", "facultyId", "sectionId",
                    "studentCollegeEmail", "studentRollNo")) claims.remove(key);
            if (!role.isEmpty()) {
                claims.put("institutionId", principal.institutionId());
                claims.put("role", role);
                if (departmentId != null) claims.put("departmentId", departmentId);
                if (facultyId != null) claims.put("facultyId", facultyId);
                if (sectionId != null) claims.put("sectionId", sectionId);
                if (studentCollegeEmail != null) claims.put("studentCollegeEmail", studentCollegeEmail);
                if (studentRollNo != null) claims.put("studentRollNo", studentRollNo);
            }
            firebaseAuth.setCustomUserClaims(uid, claims);

            String logId = UUID.randomUUID().toString();
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("actorUid", principal.uid());
            log.put("action", role.isEmpty() ? "USER_ROLE_REMOVED" : "USER_ROLE_ASSIGNED");
            log.put("targetUid", uid);
            log.put("role", role.isEmpty() ? "UNASSIGNED" : role);
            log.put("createdAt", Instant.now().toString());
            database.write(database.tenantPath(principal.institutionId(), "auditLogs", logId), log);

            return Map.of("uid", uid, "role", role.isEmpty() ? "UNASSIGNED" : role,
                    "message", "Role updated. Access will refresh automatically within 25 seconds.");
        } catch (FirebaseAuthException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not update this Firebase user role.", ex);
        }
    }

    private void verifyRecord(String institutionId, String collection, String id, String label) {
        if (id != null && database.read(database.tenantPath(institutionId, collection, id)) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " was not found in this institution.");
        }
    }

    private boolean isOnlySuperAdmin(String institutionId, String targetUid) throws FirebaseAuthException {
        int otherAdmins = 0;
        for (UserRecord user : firebaseAuth.listUsers(null, 1000).iterateAll()) {
            if (targetUid.equals(user.getUid())) continue;
            Map<String, Object> claims = user.getCustomClaims();
            if (institutionId.equals(claimString(claims, "institutionId"))
                    && "SUPER_ADMIN".equals(claimString(claims, "role"))) otherAdmins++;
        }
        return otherAdmins == 0;
    }

    private List<Map<String, Object>> facultyOptions(Map<String, Object> facultyRecords, Map<String, Object> departments) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (Map.Entry<String, Object> entry : facultyRecords.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> record)) continue;
            Object rawEmail = record.get("college_email");
            if (!(rawEmail instanceof String) || ((String) rawEmail).isBlank()) rawEmail = record.get("email");
            if (!(rawEmail instanceof String) || !validEmail(((String) rawEmail).trim())) continue;
            String email = ((String) rawEmail).trim();
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", entry.getKey());
            option.put("name", Objects.toString(record.get("name"), entry.getKey()));
            option.put("email", email);
            String departmentId = Objects.toString(record.get("department_id"), "");
            option.put("departmentId", departmentId);
            Object department = departments.get(departmentId);
            option.put("departmentName", department instanceof Map<?, ?> item
                    ? Objects.toString(item.get("name"), departmentId) : departmentId);
            options.add(option);
        }
        options.sort(Comparator.comparing(item -> Objects.toString(item.get("name"), ""), String.CASE_INSENSITIVE_ORDER));
        return options;
    }

    private List<Map<String, Object>> sectionOptions(Map<String, Object> sectionRecords, Map<String, Object> departments) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (Map.Entry<String, Object> entry : sectionRecords.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> record)) continue;
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", entry.getKey());
            option.put("name", Objects.toString(record.get("name"), entry.getKey()));
            option.put("shiftId", Objects.toString(record.get("shift_id"), ""));
            String departmentId = Objects.toString(record.get("department_id"), "");
            option.put("departmentId", departmentId);
            Object department = departments.get(departmentId);
            if (department instanceof Map<?, ?> item) {
                option.put("departmentName", Objects.toString(item.get("name"), departmentId));
                option.put("departmentBlockNo", departmentBlockNumber(item.get("block_no")));
                option.put("departmentCode", Objects.toString(item.get("code"), ""));
            } else {
                option.put("departmentName", departmentId);
                option.put("departmentBlockNo", "");
                option.put("departmentCode", "");
            }
            options.add(option);
        }
        options.sort(Comparator.comparing(item -> Objects.toString(item.get("name"), ""), String.CASE_INSENSITIVE_ORDER));
        return options;
    }

    private String departmentBlockNumber(Object value) {
        String blockNo = Objects.toString(value, "").trim();
        return blockNo.matches("[0-9]") ? "0" + blockNo : blockNo;
    }

    private String facultyCollegeEmail(Map<String, Object> facultyRecords, String facultyId) {
        if (facultyId == null) return null;
        Object value = facultyRecords.get(facultyId);
        if (value instanceof Map<?, ?> record) {
            Object email = record.get("college_email");
            if (!(email instanceof String) || ((String) email).isBlank()) email = record.get("email");
            if (email instanceof String && !((String) email).isBlank()) return (String) email;
        }
        return facultyId;
    }

    private boolean validEmail(String email) {
        return email.length() <= 254 && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private String optionalEmail(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String email = value.trim();
        if (!validEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be a valid email address.");
        }
        return email;
    }

    private String resolveFacultyId(String institutionId, String value) {
        if (value == null || value.isBlank()) return null;
        String entered = value.trim();
        if (!entered.contains("@")) return optionalId(entered, "facultyId");
        if (entered.length() > 254 || !entered.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faculty college email must be a valid email address.");
        }

        Map<String, Object> records = database.readMap(database.tenantPath(institutionId, "faculty"));
        String match = null;
        for (Map.Entry<String, Object> entry : records.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> record)) continue;
            Object email = record.get("college_email");
            if (!(email instanceof String) || ((String) email).isBlank()) email = record.get("email");
            if (email instanceof String && entered.equalsIgnoreCase(((String) email).trim())) {
                if (match != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "More than one faculty record uses that college email. Make faculty emails unique first.");
                }
                match = entry.getKey();
            }
        }
        if (match == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Faculty college email was not found under Academic data.");
        }
        return match;
    }

    private String optionalStudentRollNo(String value) {
        if (value == null || value.isBlank()) return null;
        String rollNo = value.trim();
        if (rollNo.length() > 64 || rollNo.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Student roll number must be at most 64 characters and contain no control characters.");
        }
        return rollNo;
    }

    private String optionalId(String value, String name) {
        if (value == null || value.isBlank()) return null;
        String id = value.trim();
        if (!SAFE_ID.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " contains unsupported characters.");
        }
        return id;
    }

    private String claimString(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    public record RoleAssignment(String role, String departmentId, String facultyId, String sectionId,
                                 String studentCollegeEmail, String studentRollNo) {}
}
