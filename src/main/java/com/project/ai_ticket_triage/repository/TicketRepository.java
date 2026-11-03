package com.project.ai_ticket_triage.repository;

import com.project.ai_ticket_triage.model.Ticket;
import com.project.ai_ticket_triage.model.TicketStatus;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;

/**
 * Data access layer for tickets. Spring Data generates the implementation at
 * startup, so save(), findById(), findAll() and others work with no code here.
 */
public interface TicketRepository extends JpaRepository<Ticket, Long> {
    /**
     * Tickets for the live queue: everything that is not resolved, plus tickets
     * resolved after the cutoff. Older resolved tickets stay in the database
     * but are not returned here. Newest first, limited by the Pageable.
     */
    @Query("select t from Ticket t "
            + "where t.status <> :resolved or t.resolvedAt > :cutoff "
            + "order by t.id desc")
    List<Ticket> findVisible(@Param("resolved") TicketStatus resolved,
                             @Param("cutoff") Instant cutoff,
                             Pageable pageable);
}
