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

/** The Anthropic provider (the Messages API). */
@Component
public class AnthropicProvider implements AiProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String code() {
        return "ANTHROPIC";
    }

    @Override
    public AiResponse complete(AiRequest request) throws AiException {
        String base = request.baseUrl() == null ? "https://api.anthropic.com" : request.baseUrl().trim();
        String url = base.endsWith("/") ? base + "v1/messages" : base + "/v1/messages";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.put("system", request.systemPrompt());
        }
        List<Map<String, String>> messages = new ArrayList<>();
        for (AiTurn turn : request.messages()) {
            if (!"system".equals(turn.role())) {
                messages.add(Map.of("role", turn.role(), "content", turn.content()));
            }
        }
        body.put("messages", messages);

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build()) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofMillis(request.timeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (request.apiKey() != null && !request.apiKey().isBlank()) {
                builder.header("x-api-key", request.apiKey());
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new AiException("The AI provider answered HTTP " + response.statusCode(),
                        response.statusCode() >= 500, null);
            }
            JsonNode root = JSON.readTree(response.body());
            StringBuilder content = new StringBuilder();
            for (JsonNode part : root.path("content")) {
                if ("text".equals(part.path("type").asText())) {
                    content.append(part.path("text").asText(""));
                }
            }
            int promptTokens = root.path("usage").path("input_tokens").asInt(0);
            int completionTokens = root.path("usage").path("output_tokens").asInt(0);
            return new AiResponse(content.toString(), promptTokens, completionTokens, request.model());
        } catch (AiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiException("The AI provider is unreachable: " + ex.getMessage(), true, ex);
        }
    }
}
