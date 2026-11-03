package com.project.ai_ticket_triage.dto;

/**
 * A past resolved ticket retrieved from the vector store. Passed to Claude as
 * an example so its classification and reply stay consistent with history.
 */
public record SimilarCase(String category, String priority, String text, String resolution) {}
