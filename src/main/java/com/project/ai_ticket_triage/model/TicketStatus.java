package com.project.ai_ticket_triage.model;

/**
 * Lifecycle of a ticket as it moves through the pipeline.
 * NEW      - saved by the API, not yet picked up by the consumer
 * RECEIVED - the Kafka consumer has read it
 * ROUTED   - classified and assigned to a queue (added in a later task)
 */
public enum TicketStatus { NEW, RECEIVED, ROUTED }
