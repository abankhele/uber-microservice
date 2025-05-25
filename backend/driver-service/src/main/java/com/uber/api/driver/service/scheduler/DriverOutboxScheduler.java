package com.uber.api.driver.service.scheduler;

import com.uber.api.driver.service.entity.DriverOutbox;
import com.uber.api.driver.service.repository.DriverOutboxRepository;
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
public class DriverOutboxScheduler {

    private final DriverOutboxRepository driverOutboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    @Transactional
    public void processOutboxEvents() {
        List<DriverOutbox> pendingEvents = driverOutboxRepository
                .findByStatusOrderByCreatedAt(OutboxStatus.PENDING);

        if (!pendingEvents.isEmpty()) {
            log.info("Processing {} driver outbox events", pendingEvents.size());
        }

        for (DriverOutbox event : pendingEvents) {
            try {
                // Send to Kafka
                kafkaTemplate.send(event.getEventType(), event.getSagaId().toString(), event.getPayload());

                // Update status
                event.setStatus(OutboxStatus.SENT);
                event.setProcessedAt(ZonedDateTime.now());
                driverOutboxRepository.save(event);

                log.info("Successfully sent driver event {} to topic {}", event.getId(), event.getEventType());

            } catch (Exception e) {
                log.error("Failed to send driver event {} to topic {}", event.getId(), event.getEventType(), e);

                event.setStatus(OutboxStatus.FAILED);
                event.setProcessedAt(ZonedDateTime.now());
                driverOutboxRepository.save(event);
            }
        }
    }
}
