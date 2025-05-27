package com.uber.api.payment.service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.payment.service.service.PaymentDomainService;
import com.uber.api.shared.events.DriverRequestEvent;
import com.uber.api.shared.events.PaymentRequestEvent;
import com.uber.api.shared.events.PaymentResponseEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestListener {

    private final PaymentDomainService paymentDomainService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-requests", groupId = "payment-service-group")
    @Transactional
    public void handlePaymentRequest(String message) {
        log.info("Received payment request: {}", message);

        try {
            PaymentRequestEvent paymentRequest = objectMapper.readValue(message, PaymentRequestEvent.class);

            // **FIXED: Use your actual interface method**
            PaymentResponseEvent paymentResponse = paymentDomainService.processPayment(paymentRequest);
            log.info("Payment processed with result: success={}", paymentResponse.isSuccess());

            // **CRITICAL FIX: Send driver request after successful payment**
            if (paymentResponse.isSuccess()) {
                DriverRequestEvent driverRequest = DriverRequestEvent.builder()
                        .sagaId(paymentRequest.getSagaId())
                        .rideRequestId(paymentRequest.getRideRequestId())
                        .customerEmail(paymentRequest.getCustomerEmail())
                        .pickupLocation(paymentRequest.getPickupLocation())
                        .destinationLocation(paymentRequest.getDestinationLocation())
                        .estimatedPrice(paymentRequest.getAmount())
                        .build();

                // Send to driver service via outbox
                paymentDomainService.saveToOutbox(driverRequest, paymentRequest.getSagaId(), "driver-requests");
                log.info("✅ Sent driver request for ride: {}", paymentRequest.getRideRequestId());
            } else {
                log.warn("❌ Payment failed, not sending driver request for ride: {}", paymentRequest.getRideRequestId());
            }

            log.info("Payment request processed successfully for ride: {}", paymentRequest.getRideRequestId());

        } catch (Exception e) {
            log.error("Error processing payment request: {}", message, e);
        }
    }
}
