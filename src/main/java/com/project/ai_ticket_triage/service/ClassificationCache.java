package com.project.ai_ticket_triage.service;

import com.project.ai_ticket_triage.dto.ClassificationResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis cache for Claude classification results.
 *
 * Key:   "classification:" + SHA-256 of the normalized ticket text, so
 *        "Charged TWICE " and "charged twice" share one entry.
 * Value: a Redis hash (category, priority, suggestedReply), which needs no JSON library.
 * Each entry expires after a configurable TTL.
 */
@Component
public class ClassificationCache {

    private static final String PREFIX = "classification:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public ClassificationCache(StringRedisTemplate redis,
                               @Value("${triage.cache.ttl-minutes:60}") long ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** Returns the cached result for this ticket text, or empty on a cache miss. */
    public Optional<ClassificationResult> get(String subject, String body) {
        Map<Object, Object> entry = redis.opsForHash().entries(keyFor(subject, body));
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ClassificationResult(
                (String) entry.get("category"),
                (String) entry.get("priority"),
                (String) entry.get("suggestedReply")));
    }

    /** Stores a result and sets its expiry. */
    public void put(String subject, String body, ClassificationResult result) {
        String key = keyFor(subject, body);
        redis.opsForHash().putAll(key, Map.of(
                "category", result.category(),
                "priority", result.priority(),
                "suggestedReply", result.suggestedReply()));
        redis.expire(key, ttl);
    }

    /** Lowercases, trims and collapses whitespace, then hashes, so trivial differences still hit the cache. */
    private String keyFor(String subject, String body) {
        String normalized = (subject + "\n" + body).toLowerCase().trim().replaceAll("\\s+", " ");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist on every JVM
            throw new IllegalStateException(e);
        }
    }
}
