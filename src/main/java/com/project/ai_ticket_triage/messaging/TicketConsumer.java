package com.project.ai_ticket_triage.messaging;

import com.project.ai_ticket_triage.dto.ClassificationResult;
import com.project.ai_ticket_triage.model.TicketStatus;
import com.project.ai_ticket_triage.repository.TicketRepository;
import com.project.ai_ticket_triage.service.ClaudeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens to the tickets.raw topic. For each ticket it asks Claude for a
 * category, priority and suggested reply, then saves the result and marks
 * the ticket ROUTED.
 */
@Component
public class TicketConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketConsumer.class);

    private final TicketRepository repo;
    private final ClaudeClient claude;

    public TicketConsumer(TicketRepository repo, ClaudeClient claude) {
        this.repo = repo;
        this.claude = claude;
    }

    @KafkaListener(topics = TicketProducer.TOPIC)
    public void onTicket(String ticketId) {
        repo.findById(Long.valueOf(ticketId)).ifPresentOrElse(t -> {
            log.info("Received ticket {}: [{}]", t.getId(), t.getSubject());
            try {
                ClassificationResult result = claude.classify(t.getSubject(), t.getBody());
                t.setCategory(result.category());
                t.setPriority(result.priority());
                t.setSuggestedReply(result.suggestedReply());
                t.setStatus(TicketStatus.ROUTED);
                log.info("Ticket {} -> {} / {}", t.getId(), result.category(), result.priority());
            } catch (Exception e) {
                // Mark as FAILED instead of throwing, so Kafka doesn't retry the same message repeatedly
                log.error("Classification failed for ticket {}", t.getId(), e);
                t.setStatus(TicketStatus.FAILED);
            }
            repo.save(t);
        }, () -> log.warn("Ticket {} not found", ticketId));
    }
}