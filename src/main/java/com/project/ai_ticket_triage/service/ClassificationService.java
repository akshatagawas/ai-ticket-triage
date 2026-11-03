package com.project.ai_ticket_triage.service;

import com.project.ai_ticket_triage.dto.ClassificationResult;
import com.project.ai_ticket_triage.dto.SimilarCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates classification: check the Redis cache first, and on a miss
 * retrieve similar past tickets (RAG) and call Claude, then cache the answer.
 *
 * Cache and retrieval are both best-effort. If Redis or pgvector fails, the
 * ticket is still classified, only slower or with less context.
 * Hit and miss counts are exposed as Micrometer metrics for Prometheus and Grafana.
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    private final ClassificationCache cache;
    private final KnowledgeBaseService knowledgeBase;
    private final ClaudeClient claude;
    private final Counter cacheHits;
    private final Counter cacheMisses;

    public ClassificationService(ClassificationCache cache, KnowledgeBaseService knowledgeBase,
                                 ClaudeClient claude, MeterRegistry registry) {
        this.cache = cache;
        this.knowledgeBase = knowledgeBase;
        this.claude = claude;
        // Appears in Prometheus as triage_cache_requests_total{result="hit"|"miss"}
        this.cacheHits = Counter.builder("triage.cache.requests").tag("result", "hit").register(registry);
        this.cacheMisses = Counter.builder("triage.cache.requests").tag("result", "miss").register(registry);
    }

    public ClassificationResult classify(String subject, String body) {
        // 1. Cache lookup. A Redis failure is treated as a miss
        try {
            Optional<ClassificationResult> cached = cache.get(subject, body);
            if (cached.isPresent()) {
                cacheHits.increment();
                log.info("Cache HIT for [{}]", subject);
                return cached.get();
            }
        } catch (Exception e) {
            log.warn("Cache read failed, continuing without cache", e);
        }
        cacheMisses.increment();
        log.info("Cache MISS for [{}]", subject);

        // 2. Retrieve similar past tickets (best-effort)
        List<SimilarCase> similar = List.of();
        try {
            similar = knowledgeBase.findSimilar(subject, body);
            log.info("Matched {} similar cases {}", similar.size(),
                    similar.stream().map(SimilarCase::category).toList());
        } catch (Exception e) {
            log.warn("Similarity search failed, continuing without it", e);
        }

        // 3. Ask Claude. Failures here propagate so the consumer can mark the ticket FAILED
        ClassificationResult result = claude.classify(subject, body, similar);

        // 4. Cache the answer (best-effort)
        try {
            cache.put(subject, body, result);
        } catch (Exception e) {
            log.warn("Cache write failed", e);
        }
        return result;
    }
}
