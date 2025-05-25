package com.uber.api.payment.service.scheduler;

import com.uber.api.payment.service.entity.PaymentOutbox;
import com.uber.api.payment.service.repository.PaymentOutboxRepository;
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
public class PaymentOutboxScheduler {

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    @Transactional
    public void processOutboxEvents() {
        List<PaymentOutbox> pendingEvents = paymentOutboxRepository
                .findByStatusOrderByCreatedAt(OutboxStatus.PENDING);

        if (!pendingEvents.isEmpty()) {
            log.info("Processing {} payment outbox events", pendingEvents.size());
        }

        for (PaymentOutbox event : pendingEvents) {
            try {
                // Send to Kafka
                kafkaTemplate.send(event.getEventType(), event.getSagaId().toString(), event.getPayload());

                // Update status
                event.setStatus(OutboxStatus.SENT);
                event.setProcessedAt(ZonedDateTime.now());
                paymentOutboxRepository.save(event);

                log.info("Successfully sent payment event {} to topic {}", event.getId(), event.getEventType());

            } catch (Exception e) {
                log.error("Failed to send payment event {} to topic {}", event.getId(), event.getEventType(), e);

                event.setStatus(OutboxStatus.FAILED);
                event.setProcessedAt(ZonedDateTime.now());
                paymentOutboxRepository.save(event);
            }
        }
    }
}
