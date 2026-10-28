package com.rivenlabs.timetable.api;

import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import com.rivenlabs.timetable.security.AppPrincipal;
import com.rivenlabs.timetable.security.RoleGuard;
import com.rivenlabs.timetable.service.AiServiceClient;
import com.rivenlabs.timetable.service.SchedulingInputService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
public class RequirementController {
    private final RoleGuard roles;
    private final SchedulingInputService inputs;
    private final AiServiceClient ai;
    private final RealtimeDatabaseStore database;

    public RequirementController(RoleGuard roles, SchedulingInputService inputs, AiServiceClient ai, RealtimeDatabaseStore database) {
        this.roles = roles; this.inputs = inputs; this.ai = ai; this.database = database;
    }

    @PostMapping("/parse-requirements")
    public Map<String, Object> parse(@AuthenticationPrincipal AppPrincipal principal, @Valid @RequestBody ParseRequest request) {
        roles.require(principal, "SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE");
        Map<String, Object> problem = inputs.load(principal, request.termId());
        Map<String, Object> aiRequest = new LinkedHashMap<>();
        aiRequest.put("text", request.text());
        aiRequest.put("catalog", inputs.catalog(problem));
        aiRequest.put("conversation", request.conversation() == null ? List.of() : request.conversation());
        Map<String, Object> interpretation = ai.post("/api/v1/requirements/parse", aiRequest);
        if (!asList(interpretation.get("clarification_questions")).isEmpty()) interpretation.put("requires_review", true);
        String id = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> stored = new LinkedHashMap<>(interpretation);
        stored.put("termId", request.termId());
        stored.put("createdBy", principal.uid());
        if ("DEPARTMENT_ADMIN".equals(principal.role())) stored.put("departmentId", principal.departmentId());
        stored.put("status", "PENDING_REVIEW");
        stored.put("createdAt", Instant.now().toString());
        database.write(database.tenantPath(principal.institutionId(), "interpretedRequirements", id), stored);
        return Map.of("id", id, "status", "PENDING_REVIEW", "interpretation", interpretation);
    }

    @PostMapping("/policies/query")
    public Map<String, Object> policies(@AuthenticationPrincipal AppPrincipal principal, @Valid @RequestBody PolicyQuestion request) {
        if (principal.role().equals("STUDENT_VIEWER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Policy assistant access is restricted.");
        Map<String, Object> documents = database.readMap(database.tenantPath(principal.institutionId(), "policyDocuments"));
        java.util.List<Map<String, Object>> normalized = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> entry : documents.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> document = new LinkedHashMap<>((Map<String, Object>) raw);
            document.putIfAbsent("id", entry.getKey());
            java.util.List<Map<String, Object>> chunks = new java.util.ArrayList<>();
            Object rawChunks = document.get("chunks");
            if (rawChunks instanceof Map<?, ?> chunkMap) {
                for (Map.Entry<?, ?> chunk : chunkMap.entrySet()) {
                    if (chunk.getValue() instanceof Map<?, ?> map) {
                        Map<String, Object> value = new LinkedHashMap<>((Map<String, Object>) map);
                        value.putIfAbsent("id", String.valueOf(chunk.getKey()));
                        chunks.add(value);
                    }
                }
            } else if (rawChunks instanceof java.util.List<?> list) {
                for (Object chunk : list) if (chunk instanceof Map<?, ?> map) chunks.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
            document.put("chunks", chunks);
            normalized.add(document);
        }
        return ai.post("/api/v1/policies/answer", Map.of("question", request.question(), "documents", normalized));
    }

    private java.util.List<?> asList(Object value) { return value instanceof java.util.List<?> list ? list : java.util.List.of(); }

    public record ConversationTurn(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "user|assistant") String role,
                                   @NotBlank @Size(max = 4000) String content) {}
    public record ParseRequest(@NotBlank @Size(max = 4000) String text,
                               @NotBlank @Size(max = 128) String termId,
                               @Size(max = 20) List<@Valid ConversationTurn> conversation) {}
    public record PolicyQuestion(@NotBlank @Size(max = 1000) String question) {}
}
