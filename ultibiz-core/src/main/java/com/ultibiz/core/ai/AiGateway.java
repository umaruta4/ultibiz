package com.ultibiz.core.ai;

import com.ultibiz.core.infrastructure.AiRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * The gateway between the assistant and the providers. It resolves the
 * configuration of the organisation, refuses external providers when the
 * organisation says so, applies the timeout, retries once and opens a circuit
 * breaker after repeated failures — a provider outage degrades into a clear
 * message instead of a hanging page.
 */
@Service
public class AiGateway {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long OPEN_MILLIS = 60_000L;

    private final AiRepository ai;
    private final Map<String, AiProvider> providers = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> openUntil = new HashMap<>();
    private final AiSecretCipher cipher;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ai-gateway");
        thread.setDaemon(true);
        return thread;
    });

    public AiGateway(AiRepository ai, AiSecretCipher cipher, List<AiProvider> providerList) {
        this.ai = ai;
        this.cipher = cipher;
        for (AiProvider provider : providerList) {
            this.providers.put(provider.code(), provider);
        }
    }

    /** Runs a completion for the organisation. */
    public AiProvider.AiResponse complete(long organizationId, List<AiProvider.AiTurn> messages) {
        AiProviderConfig config = ai.config(organizationId);
        if (config == null || !config.active()) {
            throw new AiException("The AI assistant is not configured for this organisation",
                    false, null);
        }
        if (config.isExternal() && !config.allowExternal()) {
            throw new AiException("This organisation may not use an external AI provider. "
                    + "An administrator can enable it, or a local model can be configured.",
                    false, null);
        }
        AiProvider provider = providers.get(config.provider());
        if (provider == null) {
            throw new AiException("Unknown AI provider: " + config.provider(), false, null);
        }
        String openUntilAt = openUntil.get(config.provider()) == null ? null
                : String.valueOf(openUntil.get(config.provider()));
        Long opened = openUntil.get(config.provider());
        if (opened != null && System.currentTimeMillis() < opened) {
            throw new AiException("The AI provider is temporarily unavailable "
                    + "(circuit breaker open, retry in " + ((opened - System.currentTimeMillis()) / 1000)
                    + " s)", true, null);
        }
        AiProvider.AiRequest request = new AiProvider.AiRequest(config.model(),
                config.systemPrompt(), messages, config.temperature(), config.maxTokens(),
                config.baseUrl(), cipher.decrypt(config.apiKey()), config.timeoutMs());

        AiException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AiProvider.AiResponse response = callWithTimeout(provider, request);
                failures.remove(config.provider());
                return response;
            } catch (AiException ex) {
                last = ex;
                if (!ex.retryable()) {
                    break;
                }
            }
        }
        int count = failures.merge(config.provider(), 1, Integer::sum);
        if (count >= FAILURE_THRESHOLD) {
            openUntil.put(config.provider(), System.currentTimeMillis() + OPEN_MILLIS);
            failures.put(config.provider(), 0);
        }
        throw last == null ? new AiException("The AI provider failed") : last;
    }

    private AiProvider.AiResponse callWithTimeout(AiProvider provider, AiProvider.AiRequest request)
            throws AiException {
        Future<AiProvider.AiResponse> future = executor.submit(() -> provider.complete(request));
        try {
            return future.get(request.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new AiException("The AI provider did not answer within " + request.timeoutMs()
                    + " ms", true, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof AiException aiException) {
                throw aiException;
            }
            throw new AiException("The AI provider failed: " + cause.getMessage(), true, cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiException("The AI call was interrupted", true, ex);
        }
    }

    /** The state of the circuit breakers (for the admin dashboard). */
    public Map<String, String> breakerState() {
        Map<String, String> state = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : openUntil.entrySet()) {
            state.put(entry.getKey(), entry.getValue() > now ? "OPEN" : "CLOSED");
        }
        return state;
    }
}
