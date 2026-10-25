package com.rivenlabs.timetable.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FirebaseAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> ROLES = Set.of("SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "FACULTY", "ACADEMIC_APPROVER", "STUDENT_VIEWER");
    private final FirebaseAuth firebaseAuth;

    public FirebaseAuthenticationFilter(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/api/v1/health") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() < 16) {
            reject(response, 401, "A Firebase ID token is required.");
            return;
        }
        FirebaseToken token;
        try {
            token = firebaseAuth.verifyIdToken(header.substring(7), true);
        } catch (Exception ex) {
            SecurityContextHolder.clearContext();
            reject(response, 401, "The Firebase ID token is invalid or expired.");
            return;
        }
        try {
            Map<String, Object> claims = token.getClaims();
            String institution = stringClaim(claims, "institutionId");
            String role = stringClaim(claims, "role");
            String section = stringClaim(claims, "sectionId");
            String faculty = stringClaim(claims, "facultyId");
            String department = stringClaim(claims, "departmentId");
            if (institution == null || role == null || !ROLES.contains(role)) {
                reject(response, 403, "This account is not provisioned for timetable access.");
                return;
            }
            if (("STUDENT_VIEWER".equals(role) && section == null) || ("FACULTY".equals(role) && faculty == null)) {
                reject(response, 403, "This account is missing its section or faculty assignment.");
                return;
            }
            AppPrincipal principal = new AppPrincipal(token.getUid(), institution, role, section, faculty, department);
            var auth = new UsernamePasswordAuthenticationToken(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception ex) {
            SecurityContextHolder.clearContext();
            reject(response, 403, "This account is not provisioned for timetable access.");
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null) chain.doFilter(request, response);
    }

    private String stringClaim(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
