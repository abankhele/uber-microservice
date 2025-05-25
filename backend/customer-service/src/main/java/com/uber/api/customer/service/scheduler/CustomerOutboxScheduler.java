package com.uber.api.customer.service.scheduler;

import com.uber.api.customer.service.entity.CustomerOutbox;
import com.uber.api.customer.service.repository.CustomerOutboxRepository;
import com.uber.api.shared.outbox.OutboxStatus;
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
public class CustomerOutboxScheduler {

    private final CustomerOutboxRepository customerOutboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    @Transactional
    public void processOutboxEvents() {
        List<CustomerOutbox> pendingEvents = customerOutboxRepository
                .findByStatusOrderByCreatedAt(OutboxStatus.PENDING);

        if (!pendingEvents.isEmpty()) {
            log.info("Processing {} customer outbox events", pendingEvents.size());
        }

        for (CustomerOutbox event : pendingEvents) {
            try {
                // Send to Kafka
                kafkaTemplate.send(event.getEventType(), event.getSagaId().toString(), event.getPayload());

                // Update status
                event.setStatus(OutboxStatus.SENT);
                event.setProcessedAt(ZonedDateTime.now());
                customerOutboxRepository.save(event);

                log.info("Successfully sent event {} to topic {}", event.getId(), event.getEventType());

            } catch (Exception e) {
                log.error("Failed to send event {} to topic {}", event.getId(), event.getEventType(), e);

                event.setStatus(OutboxStatus.FAILED);
                event.setProcessedAt(ZonedDateTime.now());
                customerOutboxRepository.save(event);
            }
        }
    }
}
