package com.rivenlabs.timetable.service;

import com.rivenlabs.timetable.data.RealtimeDatabaseStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TimetableGenerationService {
    private final RealtimeDatabaseStore database;
    private final AiServiceClient ai;

    public TimetableGenerationService(RealtimeDatabaseStore database, AiServiceClient ai) { this.database = database; this.ai = ai; }

    @Async("generationExecutor")
    public void run(String institutionId, String userId, String departmentId, String jobId, String interpretationId, String termId, Map<String, Object> problem) {
        String root = database.tenantPath(institutionId);
        String jobPath = root + "/generationJobs/" + jobId;
        try {
            database.update(jobPath, Map.of("status", "RUNNING", "startedAt", Instant.now().toString()));
            Map<String, Object> result = ai.post("/api/v1/timetables/solve", problem);
            Object validation = result.get("validation");
            boolean valid = validation instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("valid"));
            String solverStatus = String.valueOf(result.get("status"));
            if (!valid || !("OPTIMAL".equals(solverStatus) || "FEASIBLE".equals(solverStatus))) {
                database.update(jobPath, Map.of("status", "FAILED", "finishedAt", Instant.now().toString(),
                        "solverStatus", solverStatus, "diagnostics", result.getOrDefault("diagnostics", java.util.List.of()),
                        "message", "No independently validated feasible timetable was produced."));
                return;
            }
            String draftId = UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("id", draftId); draft.put("termId", termId); draft.put("status", "DRAFT");
            if (departmentId != null) draft.put("departmentId", departmentId);
            draft.put("solverStatus", solverStatus); draft.put("entries", result.getOrDefault("entries", java.util.List.of()));
            draft.put("validation", validation); draft.put("diagnostics", result.getOrDefault("diagnostics", java.util.List.of()));
            draft.put("objectiveValue", result.get("objective_value"));
            draft.put("generatedBy", userId); draft.put("interpretationId", interpretationId); draft.put("createdAt", Instant.now().toString());
            database.write(root + "/timetableDrafts/" + draftId, draft);
            database.update(jobPath, Map.of("status", "COMPLETED", "finishedAt", Instant.now().toString(), "draftId", draftId,
                    "solverStatus", solverStatus, "message", "A validated timetable draft is ready for review."));
        } catch (Exception ex) {
            database.update(jobPath, Map.of("status", "FAILED", "finishedAt", Instant.now().toString(),
                    "message", "Timetable generation failed. Check service configuration and scheduling input."));
        }
    }
}
