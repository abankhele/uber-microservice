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
        List<PaymentOutbox> pendingEvents = paymentOutboxRepository.findByStatusOrderByCreatedAt(OutboxStatus.PENDING);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Processing {} payment outbox events", pendingEvents.size());

        for (PaymentOutbox event : pendingEvents) {
            try {
                String topic = getTopicForEventType(event.getEventType());
                kafkaTemplate.send(topic, event.getPayload());

                event.setStatus(OutboxStatus.SENT);
                event.setProcessedAt(ZonedDateTime.now());
                paymentOutboxRepository.save(event);

                log.info("Successfully sent payment event {} to topic {}", event.getId(), topic);

            } catch (Exception e) {
                log.error("Failed to send payment event {}", event.getId(), e);
                event.setStatus(OutboxStatus.FAILED);
                paymentOutboxRepository.save(event);
            }
        }
    }

    private String getTopicForEventType(String eventType) {
        return switch (eventType) {
            case "payment-responses" -> "payment-responses";
            case "driver-requests" -> "driver-requests";
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }

}
