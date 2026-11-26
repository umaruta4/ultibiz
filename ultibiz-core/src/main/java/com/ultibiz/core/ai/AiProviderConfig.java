package com.ultibiz.core.ai;

/** The AI configuration of one organisation. */
public record AiProviderConfig(
        long id,
        long organizationId,
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        double temperature,
        int timeoutMs,
        int maxTokens,
        boolean allowExternal,
        String systemPrompt,
        boolean active) {

    /** Is the configured provider outside the organisation? */
    public boolean isExternal() {
        return !"ECHO".equals(provider) && !"OLLAMA".equals(provider);
    }
}
