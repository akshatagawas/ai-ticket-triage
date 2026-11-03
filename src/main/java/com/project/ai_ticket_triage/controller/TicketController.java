package com.project.ai_ticket_triage.controller;

import com.project.ai_ticket_triage.messaging.TicketProducer;
import com.project.ai_ticket_triage.model.Ticket;
import com.project.ai_ticket_triage.model.TicketStatus;
import com.project.ai_ticket_triage.repository.TicketRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for tickets.
 * POST /tickets              - create a ticket and publish it to Kafka
 * GET  /tickets              - live queue: open tickets plus recently resolved ones
 * GET  /tickets/{id}         - fetch any ticket by number, including old resolved ones
 * POST /tickets/{id}/resolve - mark a ticket resolved
 */
@RestController
@RequestMapping("/tickets")
public class TicketController {

    /** Shape of the JSON body expected by POST /tickets. */
    public record CreateTicketRequest(String subject, String body) {}

    private final TicketRepository repo;
    private final TicketProducer producer;
    private final Duration resolvedVisibleFor;

    public TicketController(TicketRepository repo, TicketProducer producer,
                            @Value("${triage.resolved-visible-minutes:5}") long visibleMinutes) {
        this.repo = repo;
        this.producer = producer;
        this.resolvedVisibleFor = Duration.ofMinutes(visibleMinutes);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateTicketRequest req) {
        // Save first so the ticket has an id before it is published
        Ticket t = new Ticket();
        t.setSubject(req.subject());
        t.setBody(req.body());
        t = repo.save(t);

        // Hand off to Kafka, and processing happens asynchronously in the consumer
        producer.publish(t.getId());

        // 202 Accepted: the ticket is stored, but processing is not finished yet
        return ResponseEntity.accepted().body(Map.of("id", t.getId(), "status", t.getStatus()));
    }

    /** The live queue: newest 50 tickets that are open or were resolved within the visibility window. */
    @GetMapping
    public List<Ticket> list() {
        Instant cutoff = Instant.now().minus(resolvedVisibleFor);
        return repo.findVisible(TicketStatus.RESOLVED, cutoff, PageRequest.of(0, 50));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Ticket> get(@PathVariable Long id) {
        // 200 with the ticket if it exists (resolved or not), otherwise 404
        return repo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Marks a ticket resolved. Only finished tickets (ROUTED or FAILED) can be
     * resolved, so this never races with the Kafka consumer still working on it.
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<Ticket> resolve(@PathVariable Long id) {
        return repo.findById(id).map(t -> {
            if (t.getStatus() == TicketStatus.RESOLVED) {
                return ResponseEntity.ok(t);                        // already resolved, nothing to do
            }
            if (t.getStatus() != TicketStatus.ROUTED && t.getStatus() != TicketStatus.FAILED) {
                return ResponseEntity.status(HttpStatus.CONFLICT).<Ticket>build();  // still being processed
            }
            t.setStatus(TicketStatus.RESOLVED);
            t.setResolvedAt(Instant.now());
            return ResponseEntity.ok(repo.save(t));
        }).orElse(ResponseEntity.notFound().build());
    }
}