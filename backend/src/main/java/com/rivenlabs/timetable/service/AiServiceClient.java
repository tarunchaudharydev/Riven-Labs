package com.rivenlabs.timetable.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Component
public class AiServiceClient {
    private final RestClient client;
    private final String apiKey;

    public AiServiceClient(RestClient.Builder builder,
                           @Value("${app.ai.base-url}") String baseUrl,
                           @Value("${app.ai.api-key}") String apiKey) {
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String path, Object body) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_API_KEY is not configured on the backend.");
        }
        try {
            Map<?, ?> result = client.post().uri(path).header("X-AI-Service-Key", apiKey)
                    .body(body).retrieve().body(Map.class);
            return result == null ? Map.of() : (Map<String, Object>) result;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String reason = switch (ex.getStatusCode().value()) {
                case 422 -> "The AI service rejected the request data (HTTP 422). Check the backend logs for the validation details.";
                case 401, 403 -> "The AI service rejected its backend credentials. Check AI_SERVICE_API_KEY in the backend environment.";
                case 502, 503, 504 -> "The AI service is unavailable. Check that the AI service is running and AI_SERVICE_URL points to it.";
                default -> "The AI service returned HTTP " + ex.getStatusCode().value() + ". Check the AI service logs for the request details.";
            };
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, reason, ex);
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not connect to the AI service. Start it and check AI_SERVICE_URL in the backend environment.", ex);
        }
    }
}
