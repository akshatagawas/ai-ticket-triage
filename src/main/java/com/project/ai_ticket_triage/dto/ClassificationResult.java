package com.project.ai_ticket_triage.dto;

/**
 * The structured answer we ask Claude to produce for each ticket.
 * Field names must match the tool's input_schema in ClaudeClient.
 */
public record ClassificationResult(String category, String priority, String suggestedReply) {}
