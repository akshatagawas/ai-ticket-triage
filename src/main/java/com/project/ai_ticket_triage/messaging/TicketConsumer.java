package com.project.ai_ticket_triage.messaging;

import com.project.ai_ticket_triage.model.TicketStatus;
import com.project.ai_ticket_triage.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens to the tickets.raw topic. For now it logs each ticket and marks it
 * RECEIVED. Later tasks add Claude classification and routing here.
 */
@Component
public class TicketConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketConsumer.class);

    private final TicketRepository repo;

    public TicketConsumer(TicketRepository repo) {
        this.repo = repo;
    }

    /** Called by Spring for every message on the topic. */
    @KafkaListener(topics = TicketProducer.TOPIC)
    public void onTicket(String ticketId) {
        // Load the full ticket using the id from the message
        repo.findById(Long.valueOf(ticketId)).ifPresentOrElse(t -> {
            log.info("Received ticket {}: [{}] {}", t.getId(), t.getSubject(), t.getBody());
            // Record that the consumer has seen it
            t.setStatus(TicketStatus.RECEIVED);
            repo.save(t);
        }, () -> log.warn("Ticket {} not found", ticketId)); // message arrived for a missing row
    }
}
