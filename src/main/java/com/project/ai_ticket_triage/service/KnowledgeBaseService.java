package com.project.ai_ticket_triage.service;

import java.util.List;

import com.project.ai_ticket_triage.dto.SimilarCase;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Retrieval half of RAG. Embeds the incoming ticket text and asks pgvector for
 * the closest past tickets by cosine similarity.
 */
@Service
public class KnowledgeBaseService {

    private static final int TOP_K = 3;

    private final VectorStore vectorStore;

    public KnowledgeBaseService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** Returns up to 3 resolved tickets most similar to the given subject and body. */
    public List<SimilarCase> findSimilar(String subject, String body) {
        String query = (subject + " " + body).trim();
        // Spring AI embeds the query text, then runs a vector search in Postgres
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(TOP_K).build());
        if (docs == null) {
            return List.of();
        }
        return docs.stream()
                .map(d -> new SimilarCase(
                        String.valueOf(d.getMetadata().get("category")),
                        String.valueOf(d.getMetadata().get("priority")),
                        d.getText(),
                        String.valueOf(d.getMetadata().get("resolution"))))
                .toList();
    }
}
