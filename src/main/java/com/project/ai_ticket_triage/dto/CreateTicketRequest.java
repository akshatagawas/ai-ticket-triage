package com.project.ai_ticket_triage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /tickets. The size limits keep a single ticket within the
 * database column (4000 chars) and cap the tokens a ticket can send to Claude.
 */
public record CreateTicketRequest(
        @NotBlank(message = "subject is required")
        @Size(max = 200, message = "subject must be at most 200 characters")
        String subject,

        @NotBlank(message = "body is required")
        @Size(max = 3000, message = "body must be at most 3000 characters")
        String body) {}