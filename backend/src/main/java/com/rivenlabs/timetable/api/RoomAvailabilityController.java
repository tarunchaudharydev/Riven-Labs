package com.rivenlabs.timetable.api;

import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import com.rivenlabs.timetable.security.RoleGuard;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Read-only, institution-scoped room availability for the published schedule. */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomAvailabilityController {
    private final RealtimeDatabaseStore database;
    private final RoleGuard roles;

    public RoomAvailabilityController(RealtimeDatabaseStore database, RoleGuard roles) {
        this.database = database;
        this.roles = roles;
    }

    @GetMapping("/availability")
    public Map<String, Object> availability(@AuthenticationPrincipal AppPrincipal principal,
                                            @RequestParam String termId,
                                            @RequestParam(required = false) String slotId) {
        roles.require(principal, "SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE",
                "ACADEMIC_APPROVER", "STUDENT_VIEWER", "FACULTY");
        if (termId.isBlank() || !termId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid academic term is required.");
        }
        String root = database.tenantPath(principal.institutionId());
        Map<String, Object> input = database.readMap(database.tenantPath(principal.institutionId(), "schedulingInputs", termId));
        List<Map<String, Object>> slots = maps(input.get("time_slots")).stream()
                .filter(slot -> !Boolean.FALSE.equals(slot.get("is_teaching_period")))
                .sorted(Comparator.comparingInt((Map<String, Object> slot) -> dayOrder(Objects.toString(slot.get("day"), "")))
                        .thenComparingInt(slot -> integer(slot.get("period"))))
                .toList();
        if (slotId == null || slotId.isBlank()) {
            return Map.of("termId", termId, "timeSlots", slots);
        }

        Map<String, Object> selected = slots.stream()
                .filter(slot -> slotId.equals(slot.get("id")))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected teaching period is not configured for this term."));
        String day = Objects.toString(selected.get("day"), "");
        int period = integer(selected.get("period"));
        if (day.isBlank() || period < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected time slot is missing its day or period number.");
        }

        Map<String, Object> roomsById = new LinkedHashMap<>();
        database.readMap(root + "/rooms").forEach((id, value) -> mergeRoom(roomsById, id, value));
        maps(input.get("rooms")).forEach(room -> mergeRoom(roomsById, Objects.toString(room.get("id"), ""), room));

        Map<String, Object> departments = database.readMap(root + "/departments");
        Map<String, Object> active = database.readMap(database.tenantPath(principal.institutionId(), "publishedTimetables", termId, "active"));
        String draftId = Objects.toString(active.get("draftId"), "");
        List<Map<String, Object>> entries = List.of();
        if (!draftId.isBlank()) {
            Map<String, Object> published = database.readMap(database.tenantPath(principal.institutionId(),
                    "publishedTimetables", termId, "versions", draftId));
            entries = maps(published.get("entries"));
        }

        List<Map<String, Object>> rooms = new ArrayList<>();
        for (Map.Entry<String, Object> item : roomsById.entrySet()) {
            Map<String, Object> room = asMap(item.getValue());
            String departmentId = Objects.toString(room.get("department_id"), "");
            if ("DEPARTMENT_ADMIN".equals(principal.role())
                    && !departmentId.isBlank() && !departmentId.equals(principal.departmentId())) {
                continue;
            }
            Map<String, Object> department = asMap(departments.get(departmentId));
            boolean blocked = strings(room.get("unavailable_slot_ids")).contains(slotId);
            boolean occupied = !blocked && entries.stream().anyMatch(entry ->
                    Objects.equals(item.getKey(), Objects.toString(entry.get("room_id"), ""))
                            && occupies(entry, slotId, day, period));
            boolean available = !blocked && !occupied;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", item.getKey());
            result.put("name", Objects.toString(room.get("name"), item.getKey()));
            putIfPresent(result, room, "code");
            putIfPresent(result, room, "capacity");
            putIfPresent(result, room, "room_type");
            putIfPresent(result, room, "equipment");
            if (!departmentId.isBlank()) result.put("department_id", departmentId);
            if (!department.isEmpty()) {
                result.put("department_name", Objects.toString(department.get("name"), ""));
                result.put("department_code", Objects.toString(department.get("code"), ""));
                result.put("department_block_no", blockNumber(department.get("block_no")));
            } else if (departmentId.isBlank()) {
                result.put("department_name", "Shared");
            }
            result.put("available", available);
            if (!available) result.put("reason", blocked ? "Room is marked unavailable for this period." : "Room is occupied on the published timetable.");
            rooms.add(result);
        }
        rooms.sort(Comparator
                .comparing((Map<String, Object> room) -> !Boolean.TRUE.equals(room.get("available")))
                .thenComparing(room -> Objects.toString(room.get("room_type"), ""))
                .thenComparing(room -> Objects.toString(room.get("code"), Objects.toString(room.get("name"), ""))));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("termId", termId);
        response.put("day", day);
        response.put("period", period);
        response.put("timeSlot", selected);
        response.put("published", !draftId.isBlank());
        response.put("rooms", rooms);
        return response;
    }

    private void mergeRoom(Map<String, Object> target, String id, Object raw) {
        if (id.isBlank() || !(raw instanceof Map<?, ?>)) return;
        Map<String, Object> room = new LinkedHashMap<>(asMap(raw));
        room.putIfAbsent("id", id);
        Map<String, Object> previous = asMap(target.get(id));
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        merged.putAll(room);
        List<String> blocked = new ArrayList<>(strings(previous.get("unavailable_slot_ids")));
        for (String blockedSlot : strings(room.get("unavailable_slot_ids"))) {
            if (!blocked.contains(blockedSlot)) blocked.add(blockedSlot);
        }
        if (!blocked.isEmpty()) merged.put("unavailable_slot_ids", blocked);
        target.put(id, merged);
    }

    private boolean occupies(Map<String, Object> entry, String slotId, String day, int period) {
        List<String> slotIds = strings(entry.get("slot_ids"));
        if (!slotIds.isEmpty()) return slotIds.contains(slotId);
        String entryDay = Objects.toString(entry.get("day"), "").trim();
        int start = integer(entry.get("period"));
        int duration = Math.max(1, integer(entry.get("duration")));
        return normalizeDay(entryDay).equals(normalizeDay(day)) && start > 0 && period >= start && period < start + duration;
    }

    private String normalizeDay(String value) {
        String day = value.trim().toUpperCase(Locale.ROOT);
        return day.length() > 3 ? day.substring(0, 3) : day;
    }

    private int dayOrder(String value) {
        return switch (normalizeDay(value)) {
            case "MON" -> 1;
            case "TUE" -> 2;
            case "WED" -> 3;
            case "THU" -> 4;
            case "FRI" -> 5;
            case "SAT" -> 6;
            case "SUN" -> 7;
            default -> 8;
        };
    }

    private String blockNumber(Object value) {
        String block = Objects.toString(value, "");
        return block.matches("[0-9]") ? "0" + block : block;
    }

    private int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(Objects.toString(value, "")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private List<String> strings(Object value) {
        return value instanceof List<?> list
                ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
    }

    private void putIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.get(key) != null) target.put(key, source.get(key));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<Map<String, Object>> result = new ArrayList<>();
            map.forEach((key, raw) -> {
                if (raw instanceof Map<?, ?> item) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) item);
                    copy.putIfAbsent("id", Objects.toString(key));
                    result.add(copy);
                }
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item).toList();
        }
        return List.of();
    }
}
