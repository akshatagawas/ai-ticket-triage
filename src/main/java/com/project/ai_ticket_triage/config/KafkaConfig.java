package com.project.ai_ticket_triage.config;

import com.project.ai_ticket_triage.messaging.TicketProducer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka setup. Declaring a NewTopic bean makes Spring create the topic on
 * startup if it doesn't exist, so no manual topic creation is needed.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic ticketsRaw() {
        // 3 partitions allow up to 3 consumers to read in parallel (used in later optimization)
        // 1 replica because the local setup has a single broker
        return TopicBuilder.name(TicketProducer.TOPIC).partitions(3).replicas(1).build();
    }
}
