package com.ultibiz.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A rate limiter for the public endpoints (the customer portal and the
 * inbound webhook): one caller may not flood the application. The counter is
 * per IP address and per path prefix and resets after the window.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowSeconds;

    public RateLimitFilter(
            @Value("${ultibiz.rate-limit.requests:60}") int limit,
            @Value("${ultibiz.rate-limit.window-seconds:60}") long windowSeconds) {
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    /** The paths that are limited (the public ones). */
    private static boolean limited(String path) {
        return path.startsWith("/portal/") || path.startsWith("/api/exchange/inbound/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!limited(path)) {
            chain.doFilter(request, response);
            return;
        }
        String key = clientKey(request) + "|" + path;
        int count = counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (count == 1) {
            // the window of this key starts now
            scheduleReset(key);
        }
        if (count > limit) {
            LOG.warn("The rate limit was reached for {} ({} requests)", key, count);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(windowSeconds));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"too many requests\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private void scheduleReset(String key) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(windowSeconds * 1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            counters.remove(key);
        });
    }

    /** The client of the request: the forwarded address, else the remote one. */
    static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
