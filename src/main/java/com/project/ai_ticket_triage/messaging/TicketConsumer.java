package com.project.ai_ticket_triage.messaging;

import com.project.ai_ticket_triage.dto.ClassificationResult;
import com.project.ai_ticket_triage.model.TicketStatus;
import com.project.ai_ticket_triage.repository.TicketRepository;
import com.project.ai_ticket_triage.service.ClassificationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens to the tickets.raw topic. For each ticket it asks the
 * ClassificationService (cache, RAG, Claude) for a category, priority and
 * suggested reply, then saves the result and marks the ticket ROUTED.
 *
 * Metrics exposed for Prometheus:
 *   triage_tickets_processed_total{status=routed|failed}   throughput and error rate
 *   triage_ticket_end_to_end_seconds_*                     ticket creation to ROUTED, including Kafka queueing
 */
@Component
public class TicketConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketConsumer.class);

    private final TicketRepository repo;
    private final ClassificationService classifier;
    private final Counter routedCounter;
    private final Counter failedCounter;
    private final Timer endToEnd;

    public TicketConsumer(TicketRepository repo, ClassificationService classifier, MeterRegistry registry) {
        this.repo = repo;
        this.classifier = classifier;
        // Registering at startup makes the series appear at 0 before the first ticket
        this.routedCounter = Counter.builder("triage.tickets.processed").tag("status", "routed").register(registry);
        this.failedCounter = Counter.builder("triage.tickets.processed").tag("status", "failed").register(registry);
        this.endToEnd = Timer.builder("triage.ticket.end_to_end")
                .description("Time from ticket creation to ROUTED, including Kafka queueing")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofSeconds(60))
                .register(registry);
    }

    @KafkaListener(topics = TicketProducer.TOPIC)
    public void onTicket(String ticketId) {
        repo.findById(Long.valueOf(ticketId)).ifPresentOrElse(t -> {
            log.info("Received ticket {}: [{}]", t.getId(), t.getSubject());
            try {
                ClassificationResult result = classifier.classify(t.getSubject(), t.getBody());
                t.setCategory(result.category());
                t.setPriority(result.priority());
                t.setSuggestedReply(result.suggestedReply());
                t.setStatus(TicketStatus.ROUTED);
                routedCounter.increment();
                // Record how long the ticket took from creation until now
                endToEnd.record(Duration.between(t.getCreatedAt(), Instant.now()));
                log.info("Ticket {} -> {} / {}", t.getId(), result.category(), result.priority());
            } catch (Exception e) {
                // Mark as FAILED instead of throwing, so Kafka doesn't retry the same message forever
                log.error("Classification failed for ticket {}", t.getId(), e);
                t.setStatus(TicketStatus.FAILED);
                failedCounter.increment();
            }
            repo.save(t);
        }, () -> log.warn("Ticket {} not found", ticketId));
    }
}