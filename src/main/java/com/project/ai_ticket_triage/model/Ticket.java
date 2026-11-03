package com.project.ai_ticket_triage.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for a support ticket. Each instance maps to one row in the
 * "tickets" table, which Hibernate creates automatically.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String subject;

    @Column(length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    private TicketStatus status = TicketStatus.NEW;

    private Instant createdAt = Instant.now();

    // Filled in by Claude during classification
    private String category;
    private String priority;

    @Column(length = 4000)
    private String suggestedReply;

    // Set when an agent marks the ticket resolved. The row is never deleted, and only the live queue hides it
    private Instant resolvedAt;

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Long getId() { return id; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getSuggestedReply() { return suggestedReply; }
    public void setSuggestedReply(String suggestedReply) { this.suggestedReply = suggestedReply; }
}
