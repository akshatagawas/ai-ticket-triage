package com.project.ai_ticket_triage.repository;

import com.project.ai_ticket_triage.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access layer for tickets. Spring Data generates the implementation at
 * startup, so save(), findById(), findAll() and others work with no code here.
 */
public interface TicketRepository extends JpaRepository<Ticket, Long> {}
