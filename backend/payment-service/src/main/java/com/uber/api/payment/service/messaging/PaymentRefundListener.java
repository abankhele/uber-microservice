package com.uber.api.payment.service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.payment.service.service.impl.PaymentDomainServiceImpl;
import com.uber.api.shared.events.PaymentRefundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRefundListener {

    private final PaymentDomainServiceImpl paymentDomainService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-refunds", groupId = "payment-service-group")
    public void handleRefundRequest(String message) {
        log.info("Received refund request: {}", message);

        try {
            PaymentRefundEvent refundEvent = objectMapper.readValue(message, PaymentRefundEvent.class);

            // Process refund through domain service
            paymentDomainService.processRefundRequest(refundEvent);

            log.info("Refund request processed successfully for customer: {}", refundEvent.getCustomerEmail());

        } catch (Exception e) {
            log.error("Error processing refund request: {}", message, e);
        }
    }
}
