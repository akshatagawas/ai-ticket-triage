package com.project.ai_ticket_triage.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes new-ticket events to Kafka. Only the ticket id is sent, and the
 * consumer loads the full ticket from Postgres. This keeps messages small
 * and avoids JSON serialization setup.
 */
@Component
public class TicketProducer {

    public static final String TOPIC = "tickets.raw";

    private final KafkaTemplate<String, String> kafka;

    // Spring injects the KafkaTemplate, configured from application.properties
    public TicketProducer(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    /** Sends the ticket id as both key and value. The key decides which partition gets the message. */
    public void publish(Long ticketId) {
        String id = String.valueOf(ticketId);
        kafka.send(TOPIC, id, id);
    }
}
