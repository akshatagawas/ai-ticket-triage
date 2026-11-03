package com.project.ai_ticket_triage.service;

import java.util.List;
import java.util.Map;

import com.project.ai_ticket_triage.dto.ClassificationResult;
import com.project.ai_ticket_triage.dto.SimilarCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Thin client for the Anthropic Messages API (the generation half of RAG).
 *
 * To get reliable JSON we define a "tool" with a JSON schema and force Claude
 * to call it (tool_choice). The tool arguments come back as a ready-made JSON
 * object, so there is no free text to parse.
 */
@Service
public class ClaudeClient {

    private static final String SYSTEM_PROMPT = """
            You are a support ticket triage assistant. Read the ticket and classify it.
            Pick the category that best fits, pick a priority based on business impact and
            urgency, and write a short, polite, helpful reply (2-4 sentences) that a support
            agent could send. Do not promise refunds or timelines.
            When similar resolved tickets are provided, use them as guidance for category,
            priority and tone, but judge the new ticket on its own merits and never copy a
            resolution that does not fit.
            """;

    // Response shapes. Jackson ignores the JSON fields we don't declare.
    private record ClaudeResponse(List<ContentBlock> content) {}
    private record ContentBlock(String type, ClassificationResult input) {}

    private final RestClient http;
    private final String model;

    public ClaudeClient(@Value("${anthropic.api-key}") String apiKey,
                        @Value("${anthropic.model}") String model) {
        this.model = model;
        this.http = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    /** Sends the ticket plus similar past cases to Claude and returns category, priority and reply. */
    public ClassificationResult classify(String subject, String body, List<SimilarCase> similar) {
        // The tool definition: its input_schema is the JSON shape we want back
        Map<String, Object> tool = Map.of(
                "name", "classify_ticket",
                "description", "Record the classification of a support ticket.",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "category", Map.of("type", "string",
                                        "enum", List.of("BILLING", "TECHNICAL", "ACCOUNT", "SHIPPING", "GENERAL")),
                                "priority", Map.of("type", "string",
                                        "enum", List.of("LOW", "MEDIUM", "HIGH", "URGENT")),
                                "suggestedReply", Map.of("type", "string")),
                        "required", List.of("category", "priority", "suggestedReply")));

        Map<String, Object> request = Map.of(
                "model", model,
                "max_tokens", 1024,
                "system", SYSTEM_PROMPT,
                "tools", List.of(tool),
                // Force Claude to answer by calling our tool, which guarantees structured output
                "tool_choice", Map.of("type", "tool", "name", "classify_ticket"),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", buildUserMessage(subject, body, similar))));

        ClaudeResponse response = http.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ClaudeResponse.class);

        return response.content().stream()
                .filter(block -> "tool_use".equals(block.type()))
                .map(ContentBlock::input)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no tool_use block"));
    }

    /** Builds the user prompt: retrieved examples first, then the new ticket. */
    private String buildUserMessage(String subject, String body, List<SimilarCase> similar) {
        StringBuilder sb = new StringBuilder();
        if (!similar.isEmpty()) {
            sb.append("Similar tickets resolved in the past:\n");
            for (int i = 0; i < similar.size(); i++) {
                SimilarCase c = similar.get(i);
                sb.append(i + 1).append(". [").append(c.category()).append(" / ").append(c.priority())
                        .append("] ").append(c.text()).append("\n   Resolution: ")
                        .append(c.resolution()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("New ticket to classify:\nSubject: ").append(subject)
                .append("\n\nBody: ").append(body);
        return sb.toString();
    }
}