package com.project.ai_ticket_triage.config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads the past-ticket knowledge base into pgvector at startup. It only runs
 * when the vector_store table is empty, so restarts do not create duplicates.
 * Each line of seed-tickets.txt is: category|priority|subject|body|resolution
 */
@Component
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;

    public SeedDataLoader(VectorStore vectorStore, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
    }

    @Override
    @SuppressWarnings("SqlResolve")
    public void run(ApplicationArguments args) throws Exception {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class);
        if (existing != null && existing > 0) {
            log.info("Knowledge base already has {} entries, skipping seed", existing);
            return;
        }

        List<Document> docs = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("seed-tickets.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Skip blank lines and comments
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\|", 5);
                // The embedded text is subject + body, and category, priority and resolution ride along as metadata
                docs.add(Document.builder()
                        .text(p[2] + "\n" + p[3])
                        .metadata(Map.of("category", p[0], "priority", p[1], "resolution", p[4]))
                        .build());
            }
        }
        // add() embeds every document and inserts it into the vector_store table
        vectorStore.add(docs);
        log.info("Loaded {} seed tickets into the knowledge base", docs.size());
    }
}
