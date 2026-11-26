package com.ultibiz.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The native Ollama provider (a model server inside the organisation). */
@Component
public class OllamaProvider implements AiProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String code() {
        return "OLLAMA";
    }

    @Override
    public AiResponse complete(AiRequest request) throws AiException {
        String base = request.baseUrl() == null ? "http://localhost:11434" : request.baseUrl().trim();
        String url = base.endsWith("/") ? base + "api/chat" : base + "/api/chat";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("stream", false);
        body.put("options", Map.of("temperature", request.temperature()));
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        for (AiTurn turn : request.messages()) {
            messages.add(Map.of("role", turn.role(), "content", turn.content()));
        }
        body.put("messages", messages);

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build()) {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(request.timeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new AiException("The model server answered HTTP " + response.statusCode(),
                        response.statusCode() >= 500, null);
            }
            JsonNode root = JSON.readTree(response.body());
            String content = root.path("message").path("content").asText("");
            int promptTokens = root.path("prompt_eval_count").asInt(0);
            int completionTokens = root.path("eval_count").asInt(0);
            return new AiResponse(content, promptTokens, completionTokens, request.model());
        } catch (AiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiException("The model server is unreachable: " + ex.getMessage(), true, ex);
        }
    }
}
