package com.ultibiz.core.ai;

import java.util.List;

/**
 * One AI provider. The gateway hides the differences between OpenAI and the
 * OpenAI-compatible servers (llama.cpp, vLLM, Ollama, new-api), Anthropic and
 * the Ollama native API.
 */
public interface AiProvider {

    /** The provider code: OPENAI_COMPATIBLE, ANTHROPIC, OLLAMA or ECHO. */
    String code();

    /** Runs one completion. */
    AiResponse complete(AiRequest request) throws AiException;

    /** The messages of a request. */
    record AiTurn(String role, String content) {
    }

    /** A completion request. */
    record AiRequest(String model, String systemPrompt, List<AiTurn> messages, double temperature,
                     int maxTokens, String baseUrl, String apiKey, int timeoutMs) {
        public AiRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            timeoutMs = timeoutMs <= 0 ? 30000 : timeoutMs;
        }
    }

    /** A completion answer. */
    record AiResponse(String content, int promptTokens, int completionTokens, String model) {
    }
}
