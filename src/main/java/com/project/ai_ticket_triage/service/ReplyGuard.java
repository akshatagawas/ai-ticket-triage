package com.project.ai_ticket_triage.service;

import com.project.ai_ticket_triage.dto.ClassificationResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Last line of defense on Claude's output, applied before the result is saved or cached.
 *  - Category and priority must be known values (the tool schema should already guarantee this)
 *  - The reply must not be empty or very long
 *  - The reply must not contain promises or injection echoes (a keyword heuristic, not a guarantee)
 * Unsafe replies are replaced with a generic one, and each block is counted as
 * triage_guardrail_blocked_total{reason=...} in Prometheus.
 */
@Component
public class ReplyGuard {

    private static final Logger log = LoggerFactory.getLogger(ReplyGuard.class);

    static final String FALLBACK_REPLY =
            "Thanks for contacting us. An agent will review your request and follow up with you shortly.";
    private static final int MAX_REPLY_CHARS = 1000;
    private static final Set<String> CATEGORIES = Set.of("BILLING", "TECHNICAL", "ACCOUNT", "SHIPPING", "GENERAL");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");

    // Promises of money or outcomes, and signs of an injected instruction showing up in the output
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("(?i)\\b(refund|credit|compensation|chargeback)\\b[^.]{0,40}\\b(approved|guaranteed|processed|issued)\\b"),
            Pattern.compile("(?i)\\bwe (will|'ll) (refund|reimburse|credit|compensate)\\b"),
            Pattern.compile("(?i)\\bguarantee[ds]?\\b"),
            Pattern.compile("(?i)ignore (all |your |any )?(previous|prior) instructions"),
            Pattern.compile("(?i)\\b(system prompt|my instructions)\\b"));

    private final MeterRegistry registry;

    public ReplyGuard(MeterRegistry registry) {
        this.registry = registry;
    }

    public ClassificationResult enforce(ClassificationResult r) {
        String category = r.category();
        String priority = r.priority();
        String reply = r.suggestedReply();

        // Set.of(...).contains(null) throws, so check for null first
        if (category == null || !CATEGORIES.contains(category)) {
            block("invalid_label");
            category = "GENERAL";
        }
        if (priority == null || !PRIORITIES.contains(priority)) {
            block("invalid_label");
            priority = "MEDIUM";
        }

        if (reply == null || reply.isBlank()) {
            block("empty_reply");
            reply = FALLBACK_REPLY;
        } else if (reply.length() > MAX_REPLY_CHARS) {
            block("too_long");
            reply = FALLBACK_REPLY;
        } else if (FORBIDDEN.stream().anyMatch(p -> p.matcher(r.suggestedReply()).find())) {
            block("forbidden_phrase");
            reply = FALLBACK_REPLY;
        }
        return new ClassificationResult(category, priority, reply);
    }

    private void block(String reason) {
        log.warn("Guardrail triggered: {}", reason);   // the reason only, never the reply text
        registry.counter("triage.guardrail.blocked", "reason", reason).increment();
    }
}