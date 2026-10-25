package com.rivenlabs.timetable.security;

public record AppPrincipal(String uid, String institutionId, String role, String sectionId, String facultyId, String departmentId) {}
