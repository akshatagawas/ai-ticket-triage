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
 * Orchestrates classification through three tiers:
 *   1. Redis exact-match cache        (identical text, about 1 ms)
 *   2. pgvector semantic cache        (same meaning, different wording)
 *   3. RAG retrieval + Claude         (slow path, result is cached in both tiers)
 *
 * Every cache and retrieval step is best-effort: if Redis or pgvector fails,
 * the ticket is still classified, just more slowly.
 * Outcomes are exposed as triage_cache_requests_total{result=...} for Prometheus.
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    private final ClassificationCache exactCache;
    private final SemanticCache semanticCache;
    private final KnowledgeBaseService knowledgeBase;
    private final ClaudeClient claude;
    private final Counter exactHits;
    private final Counter semanticHits;
    private final Counter misses;

    public ClassificationService(ClassificationCache exactCache, SemanticCache semanticCache,
                                 KnowledgeBaseService knowledgeBase, ClaudeClient claude,
                                 MeterRegistry registry) {
        this.exactCache = exactCache;
        this.semanticCache = semanticCache;
        this.knowledgeBase = knowledgeBase;
        this.claude = claude;
        this.exactHits = Counter.builder("triage.cache.requests").tag("result", "hit_exact").register(registry);
        this.semanticHits = Counter.builder("triage.cache.requests").tag("result", "hit_semantic").register(registry);
        this.misses = Counter.builder("triage.cache.requests").tag("result", "miss").register(registry);
    }

    public ClassificationResult classify(String subject, String body) {
        // Tier 1: exact match in Redis
        try {
            Optional<ClassificationResult> exact = exactCache.get(subject, body);
            if (exact.isPresent()) {
                exactHits.increment();
                log.info("Exact cache HIT for [{}]", subject);
                return exact.get();
            }
        } catch (Exception e) {
            log.warn("Exact cache read failed, continuing", e);
        }

        // Tier 2: semantic match in pgvector
        try {
            Optional<ClassificationResult> semantic = semanticCache.get(subject, body);
            if (semantic.isPresent()) {
                semanticHits.increment();
                log.info("Semantic cache HIT for [{}]", subject);
                // Promote to Redis so the identical text is served instantly next time
                putExactQuietly(subject, body, semantic.get());
                return semantic.get();
            }
        } catch (Exception e) {
            log.warn("Semantic cache read failed, continuing", e);
        }

        misses.increment();
        log.info("Cache MISS for [{}]", subject);

        // Tier 3: RAG retrieval (best-effort) and Claude
        List<SimilarCase> similar = List.of();
        try {
            similar = knowledgeBase.findSimilar(subject, body);
            log.info("Matched {} similar cases {}", similar.size(),
                    similar.stream().map(SimilarCase::category).toList());
        } catch (Exception e) {
            log.warn("Similarity search failed, continuing without it", e);
        }

        // Failures here propagate so the consumer can mark the ticket FAILED
        ClassificationResult result = claude.classify(subject, body, similar);

        // Store in both tiers (best-effort)
        putExactQuietly(subject, body, result);
        try {
            semanticCache.put(subject, body, result);
        } catch (Exception e) {
            log.warn("Semantic cache write failed", e);
        }
        return result;
    }

    private void putExactQuietly(String subject, String body, ClassificationResult result) {
        try {
            exactCache.put(subject, body, result);
        } catch (Exception e) {
            log.warn("Exact cache write failed", e);
        }
    }
}