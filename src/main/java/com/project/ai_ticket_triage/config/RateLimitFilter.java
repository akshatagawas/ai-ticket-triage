package com.project.ai_ticket_triage.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limiter for POST /tickets, keyed by client IP.
 * One Redis counter per IP per minute: INCR the key, and reject with 429 once it passes the limit.
 * Redis keeps the count shared across app instances. If Redis is down the limiter
 * fails open, so an outage never blocks ticket intake.
 * Behind a reverse proxy, getRemoteAddr() is the proxy's address, so you would key on X-Forwarded-For instead.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final StringRedisTemplate redis;
    private final int limitPerMinute;
    private final Counter rejected;

    public RateLimitFilter(StringRedisTemplate redis,
                           @Value("${triage.rate-limit.per-minute:120}") int limitPerMinute,
                           MeterRegistry registry) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute;
        this.rejected = Counter.builder("triage.ratelimit.rejected").register(registry);
    }

    /** Only ticket creation is limited. Reads, search and metrics are never throttled. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && "/tickets".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            long window = System.currentTimeMillis() / 60_000;
            String key = "ratelimit:" + request.getRemoteAddr() + ":" + window;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofMinutes(2));   // the key outlives its window slightly, then cleans itself up
            }
            if (count != null && count > limitPerMinute) {
                rejected.increment();
                long retryAfter = 60 - (System.currentTimeMillis() / 1000 % 60);
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(retryAfter));
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"Too many requests\",\"retryAfterSeconds\":" + retryAfter + "}");
                return;
            }
        } catch (Exception e) {
            log.warn("Rate limiter unavailable, allowing request", e);
        }
        chain.doFilter(request, response);
    }
}