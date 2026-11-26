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

/**
 * The OpenAI-compatible provider: it works with OpenAI itself and with every
 * server that speaks the same protocol (llama.cpp, vLLM, Ollama's compatible
 * endpoint, new-api).
 */
@Component
public class OpenAiCompatibleProvider implements AiProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String code() {
        return "OPENAI_COMPATIBLE";
    }

    @Override
    public AiResponse complete(AiRequest request) throws AiException {
        String base = request.baseUrl() == null ? "" : request.baseUrl().trim();
        String url = base.endsWith("/") ? base + "v1/chat/completions" : base + "/v1/chat/completions";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
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
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(request.timeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (request.apiKey() != null && !request.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + request.apiKey());
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new AiException("The AI provider answered HTTP " + response.statusCode()
                        + ": " + truncate(response.body()), response.statusCode() >= 500, null);
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiException("The AI provider returned no choice");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            return new AiResponse(content, promptTokens, completionTokens, request.model());
        } catch (AiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiException("The AI provider is unreachable: " + ex.getMessage(), true, ex);
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
