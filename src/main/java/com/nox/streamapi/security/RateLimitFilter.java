package com.nox.streamapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED = 10_000;

    private static final class Window {
        final long start;
        volatile int count;

        Window(long start) {
            this.start = start;
        }
    }

    private final int limit;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${RATE_LIMIT:120}") int limit) {
        this.limit = limit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long now = System.currentTimeMillis();

        if (windows.size() > MAX_TRACKED) {
            windows.entrySet().removeIf(e -> now - e.getValue().start >= WINDOW_MS);
        }

        Window w = windows.compute(clientId(request), (k, old) -> {
            if (old == null || now - old.start >= WINDOW_MS) {
                Window fresh = new Window(now);
                fresh.count = 1;
                return fresh;
            }
            old.count++;
            return old;
        });

        if (w.count > limit) {
            long retryAfter = Math.max(1L, (w.start + WINDOW_MS - now + 999L) / 1000L);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"error\":{\"code\":\"RATE_LIMITED\","
                            + "\"message\":\"Too many requests. Try again later.\"}}");
            return;
        }

        chain.doFilter(request, response);
    }

    private static String clientId(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
