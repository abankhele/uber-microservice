package com.uber.api.payment.service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.payment.service.service.PaymentDomainService;
import com.uber.api.shared.events.PaymentRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestListener {

    private final PaymentDomainService paymentDomainService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-requests", groupId = "payment-service-group")
    public void handlePaymentRequest(String message) {
        log.info("Received payment request: {}", message);

        try {
            PaymentRequestEvent paymentRequest = objectMapper.readValue(message, PaymentRequestEvent.class);

            // Process payment through domain service
            paymentDomainService.processPayment(paymentRequest);

            log.info("Payment request processed successfully for ride: {}", paymentRequest.getRideRequestId());

        } catch (Exception e) {
            log.error("Error processing payment request: {}", message, e);
        }
    }
}
