package com.uber.api.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository
                .findByStatusOrderByCreatedAt(OutboxStatus.PENDING);

        log.info("Processing {} outbox events", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                // Send to Kafka
                kafkaTemplate.send(event.getEventType(), event.getSagaId().toString(), event.getPayload());

                // Update status
                event.setStatus(OutboxStatus.SENT);
                event.setProcessedAt(ZonedDateTime.now());
                outboxEventRepository.save(event);

                log.info("Successfully sent event {} to topic {}", event.getId(), event.getEventType());

            } catch (Exception e) {
                log.error("Failed to send event {} to topic {}", event.getId(), event.getEventType(), e);

                event.setStatus(OutboxStatus.FAILED);
                event.setProcessedAt(ZonedDateTime.now());
                outboxEventRepository.save(event);
            }
        }
    }
}
