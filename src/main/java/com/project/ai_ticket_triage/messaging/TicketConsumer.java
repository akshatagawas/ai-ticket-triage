package com.project.ai_ticket_triage.messaging;

import com.project.ai_ticket_triage.dto.ClassificationResult;
import com.project.ai_ticket_triage.dto.SimilarCase;
import com.project.ai_ticket_triage.model.TicketStatus;
import com.project.ai_ticket_triage.repository.TicketRepository;
import com.project.ai_ticket_triage.service.ClaudeClient;
import com.project.ai_ticket_triage.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Listens to the tickets.raw topic. For each ticket it retrieves similar past
 * tickets (RAG), asks Claude for a category, priority and suggested reply, then
 * saves the result and marks the ticket ROUTED.
 */
@Component
public class TicketConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketConsumer.class);

    private final TicketRepository repo;
    private final ClaudeClient claude;
    private final KnowledgeBaseService knowledgeBase;

    public TicketConsumer(TicketRepository repo, ClaudeClient claude, KnowledgeBaseService knowledgeBase) {
        this.repo = repo;
        this.claude = claude;
        this.knowledgeBase = knowledgeBase;
    }

    @KafkaListener(topics = TicketProducer.TOPIC)
    public void onTicket(String ticketId) {
        repo.findById(Long.valueOf(ticketId)).ifPresentOrElse(t -> {
            log.info("Received ticket {}: [{}]", t.getId(), t.getSubject());
            try {
                // Retrieval is best-effort: if it fails, classify without examples
                List<SimilarCase> similar = List.of();
                try {
                    similar = knowledgeBase.findSimilar(t.getSubject(), t.getBody());
                    log.info("Ticket {} matched {} similar cases", t.getId(),
                            similar.stream().map(SimilarCase::category).toList());
                } catch (Exception e) {
                    log.warn("Similarity search failed for ticket {}, continuing without it", t.getId(), e);
                }

                ClassificationResult result = claude.classify(t.getSubject(), t.getBody(), similar);
                t.setCategory(result.category());
                t.setPriority(result.priority());
                t.setSuggestedReply(result.suggestedReply());
                t.setStatus(TicketStatus.ROUTED);
                log.info("Ticket {} -> {} / {}", t.getId(), result.category(), result.priority());
            } catch (Exception e) {
                log.error("Classification failed for ticket {}", t.getId(), e);
                t.setStatus(TicketStatus.FAILED);
            }
            repo.save(t);
        }, () -> log.warn("Ticket {} not found", ticketId));
    }
}