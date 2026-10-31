package com.project.ai_ticket_triage.service;

import java.util.List;
import java.util.Map;

import com.project.ai_ticket_triage.dto.ClassificationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Thin client for the Anthropic Messages API.
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
            """;

    // Response shapes. Jackson ignores the JSON fields we don't declare.
    private record ClaudeResponse(List<ContentBlock> content) {}
    private record ContentBlock(String type, ClassificationResult input) {}

    private final RestClient http;
    private final String model;

    public ClaudeClient(@Value("${anthropic.api-key}") String apiKey,
                        @Value("${anthropic.model}") String model) {
        this.model = model;
        // Fixed headers sent on every request
        this.http = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    /** Sends the ticket to Claude and returns the category, priority and suggested reply. */
    public ClassificationResult classify(String subject, String body) {
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
                        "content", "Subject: " + subject + "\n\nBody: " + body)));

        ClaudeResponse response = http.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ClaudeResponse.class);

        // Find the tool_use block, which holds our JSON result
        return response.content().stream()
                .filter(block -> "tool_use".equals(block.type()))
                .map(ContentBlock::input)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no tool_use block"));
    }
}
