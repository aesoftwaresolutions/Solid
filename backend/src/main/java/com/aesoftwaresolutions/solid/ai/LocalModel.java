package com.aesoftwaresolutions.solid.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A thin client for an Ollama-compatible server running next to Solid.
 *
 * <p>It asks for JSON and returns the parsed answer, or empty. Every failure — disabled, unreachable, slow,
 * not JSON — is the same empty answer: a suggestion is a nicety, never something the books depend on.
 */
@Component
public class LocalModel {

    private static final Logger log = LoggerFactory.getLogger(LocalModel.class);

    private final boolean enabled;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper json;
    private final HttpClient http;

    LocalModel(@Value("${solid.ai.enabled:false}") boolean enabled,
               @Value("${solid.ai.base-url:http://localhost:11434}") String baseUrl,
               @Value("${solid.ai.model:llama3.2}") String model,
               @Value("${solid.ai.timeout-seconds:8}") int timeoutSeconds,
               ObjectMapper json) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public boolean enabled() {
        return enabled;
    }

    public String model() {
        return model;
    }

    /**
     * Sends a prompt and returns the model's answer parsed as JSON.
     *
     * @return the answer, or empty when the model is off, unreachable, too slow, or did not return JSON
     */
    public Optional<JsonNode> askForJson(String prompt) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false,
                    "format", "json",
                    "options", Map.of("temperature", 0)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Local model returned HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode envelope = json.readTree(response.body());
            String answer = envelope.path("response").asText("");
            return answer.isBlank() ? Optional.empty() : Optional.of(json.readTree(answer));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            // A missing or slow model must never break bookkeeping.
            log.debug("Local model unavailable: {}", e.toString());
            return Optional.empty();
        }
    }
}
