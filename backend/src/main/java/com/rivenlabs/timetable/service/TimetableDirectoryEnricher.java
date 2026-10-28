package com.rivenlabs.timetable.service;

import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class TimetableDirectoryEnricher {
    private final RealtimeDatabaseStore database;

    public TimetableDirectoryEnricher(RealtimeDatabaseStore database) {
        this.database = database;
    }

    public List<Map<String, Object>> enrich(String institutionId, String termId,
                                             List<Map<String, Object>> source, boolean applySubstitutions) {
        String root = database.tenantPath(institutionId);
        Map<String, Object> faculty = database.readMap(root + "/faculty");
        Map<String, Object> rooms = database.readMap(root + "/rooms");
        Map<String, Object> sections = database.readMap(root + "/sections");
        Map<String, Object> departments = database.readMap(root + "/departments");
        Map<String, Object> input = database.readMap(database.tenantPath(institutionId, "schedulingInputs", termId));
        List<Map<String, Object>> slots = rows(input.get("time_slots"));
        Map<String, Object> substitutions = applySubstitutions
                ? database.readMap(database.tenantPath(institutionId, "activeSubstitutions", termId))
                : Map.of();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> raw : source) {
            Map<String, Object> entry = new LinkedHashMap<>(raw);
            String substitutionKey = Objects.toString(entry.get("requirement_id"), "") + "_"
                    + Objects.toString(entry.get("day"), "").toUpperCase(Locale.ROOT) + "_"
                    + Objects.toString(entry.get("period"), "");
            Map<String, Object> overlay = asMap(substitutions.get(substitutionKey));
            if (!overlay.isEmpty()) {
                putIfPresent(entry, overlay, "substituteFacultyId", "faculty_id");
                putIfPresent(entry, overlay, "substituteFacultyName", "faculty_name");
                entry.put("substitute", true);
            }

            String sectionId = Objects.toString(entry.get("section_id"), "");
            Map<String, Object> section = asMap(sections.get(sectionId));
            putIfPresent(entry, section, "name", "section_name");
            putIfPresent(entry, section, "code", "section_code");

            String facultyId = Objects.toString(entry.get("faculty_id"), "");
            Map<String, Object> member = asMap(faculty.get(facultyId));
            if (entry.get("faculty_name") == null) putIfPresent(entry, member, "name", "faculty_name");
            putIfPresent(entry, member, "cabin_no", "faculty_cabin_no");

            String roomId = Objects.toString(entry.get("room_id"), "");
            Map<String, Object> room = asMap(rooms.get(roomId));
            putIfPresent(entry, room, "name", "room_name");
            putIfPresent(entry, room, "code", "room_code");
            putIfPresent(entry, room, "room_type", "room_type");
            putIfPresent(entry, room, "building", "room_building");
            putIfPresent(entry, room, "block", "room_building");

            String departmentId = Objects.toString(entry.get("department_id"),
                    Objects.toString(section.get("department_id"), Objects.toString(member.get("department_id"), "")));
            Map<String, Object> department = asMap(departments.get(departmentId));
            if (!departmentId.isBlank()) entry.put("department_id", departmentId);
            putIfPresent(entry, department, "name", "department_name");
            putIfPresent(entry, department, "code", "department_code");
            if (!department.isEmpty()) entry.put("department_block_no", blockNumber(department.get("block_no")));

            String roomDepartmentId = Objects.toString(room.get("department_id"), "");
            Map<String, Object> roomDepartment = asMap(departments.get(roomDepartmentId));
            if (!roomDepartment.isEmpty()) {
                putIfPresent(entry, roomDepartment, "name", "room_department_name");
                entry.put("room_department_block_no", blockNumber(roomDepartment.get("block_no")));
            }

            List<String> slotIds = strings(entry.get("slot_ids"));
            int startPeriod = integer(entry.get("period"));
            String entryDay = Objects.toString(entry.get("day"), "");
            List<Map<String, Object>> entrySlots = slots.stream()
                    .filter(slot -> slotMatches(slot, slotIds, entryDay, startPeriod, integer(entry.get("duration"))))
                    .sorted(Comparator.comparingInt(slot -> integer(slot.get("period"))))
                    .toList();
            if (!entrySlots.isEmpty()) {
                Map<String, Object> first = entrySlots.get(0);
                Map<String, Object> last = entrySlots.get(entrySlots.size() - 1);
                putIfPresent(entry, first, "label", "time_slot_label");
                putIfPresent(entry, first, "start_time", "time_start");
                putIfPresent(entry, last, "end_time", "time_end");
            }
            if (entry.get("duration") instanceof Number duration) entry.put("duration_hours", duration.intValue());
            result.add(entry);
        }
        return result;
    }

    private boolean slotMatches(Map<String, Object> slot, List<String> slotIds, String day, int period, int duration) {
        String id = Objects.toString(slot.get("id"), "");
        if (!slotIds.isEmpty()) return slotIds.contains(id);
        int slotPeriod = integer(slot.get("period"));
        return normalizeDay(Objects.toString(slot.get("day"), "")).equals(normalizeDay(day))
                && period > 0 && slotPeriod >= period && slotPeriod < period + Math.max(1, duration);
    }

    private String normalizeDay(String value) {
        String day = value.trim().toUpperCase(Locale.ROOT);
        return day.length() > 3 ? day.substring(0, 3) : day;
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

    private void putIfPresent(Map<String, Object> target, Map<String, Object> source, String sourceKey, String targetKey) {
        if (source.get(sourceKey) != null) target.put(targetKey, source.get(sourceKey));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value) {
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
