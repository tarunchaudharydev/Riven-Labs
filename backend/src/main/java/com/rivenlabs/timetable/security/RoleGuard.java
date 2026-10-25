package com.rivenlabs.timetable.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Component
public class RoleGuard {
    public void require(AppPrincipal principal, String... allowed) {
        if (!Set.of(allowed).contains(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account cannot perform this operation.");
        }
    }
}
