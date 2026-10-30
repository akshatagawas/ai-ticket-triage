package com.project.ai_ticket_triage.controller;

import java.util.List;
import java.util.Map;

import com.project.ai_ticket_triage.messaging.TicketProducer;
import com.project.ai_ticket_triage.model.Ticket;
import com.project.ai_ticket_triage.repository.TicketRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for tickets.
 * POST /tickets      - create a ticket and publish it to Kafka
 * GET  /tickets      - list all tickets
 * GET  /tickets/{id} - fetch one ticket
 */
@RestController
@RequestMapping("/tickets")
public class TicketController {

    /** Shape of the JSON body expected by POST /tickets. */
    public record CreateTicketRequest(String subject, String body) {}

    private final TicketRepository repo;
    private final TicketProducer producer;

    public TicketController(TicketRepository repo, TicketProducer producer) {
        this.repo = repo;
        this.producer = producer;
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

    @GetMapping
    public List<Ticket> list() {
        return repo.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Ticket> get(@PathVariable Long id) {
        // 200 with the ticket if it exists, otherwise 404
        return repo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
