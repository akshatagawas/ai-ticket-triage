package com.project.ai_ticket_triage.service;

import com.project.ai_ticket_triage.dto.ClassificationResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Semantic cache: reuses a previous classification when a new ticket is close
 * in meaning to one already classified, even if the wording differs.
 *
 * Entries live in the same pgvector table as the knowledge base but are
 * tagged source=cache, so they never appear as RAG examples. The classification
 * result is stored in the row's metadata.
 */
@Component
public class SemanticCache {

    private final VectorStore vectorStore;
    private final double threshold;

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    public SemanticCache(VectorStore vectorStore,
                         @Value("${triage.semantic-cache.threshold:0.85}") double threshold) {
        this.vectorStore = vectorStore;
        this.threshold = threshold;
    }

    /** Returns a stored result for a close-enough ticket, or empty if none passes the threshold. */
    public Optional<ClassificationResult> get(String subject, String body) {
        // Fetch the closest cached ticket with no threshold so we can log its score, then apply the threshold ourselves
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(subject + "\n" + body)   // same text shape used when storing
                        .topK(1)
                        .filterExpression("source == 'cache'")
                        .build());
        if (hits == null || hits.isEmpty()) {
            return Optional.empty();
        }
        Document best = hits.get(0);
        Double score = best.getScore();   // similarity, where 1.0 means identical
        log.info("Semantic cache best score for [{}] = {} (threshold {})", subject, score, threshold);
        if (score == null || score < threshold) {
            return Optional.empty();
        }
        Map<String, Object> m = best.getMetadata();
        return Optional.of(new ClassificationResult(
                String.valueOf(m.get("category")),
                String.valueOf(m.get("priority")),
                String.valueOf(m.get("suggestedReply"))));
    }

    /** Stores a fresh Claude result so future similar tickets can reuse it. */
    public void put(String subject, String body, ClassificationResult result) {
        vectorStore.add(List.of(Document.builder()
                .text(subject + "\n" + body)
                .metadata(Map.of(
                        "source", "cache",
                        "category", result.category(),
                        "priority", result.priority(),
                        "suggestedReply", result.suggestedReply()))
                .build()));
    }
}