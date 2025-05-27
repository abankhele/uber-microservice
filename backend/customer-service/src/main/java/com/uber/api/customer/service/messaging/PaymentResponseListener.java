package com.uber.api.customer.service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.customer.service.saga.CustomerPaymentSaga;
import com.uber.api.shared.events.PaymentResponseEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResponseListener {

    private final CustomerPaymentSaga customerPaymentSaga;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-responses", groupId = "customer-payment-response-group")
    public void handlePaymentResponse(String message) {
        log.info("Received payment response: {}", message);

        try {
            PaymentResponseEvent paymentResponse = objectMapper.readValue(message, PaymentResponseEvent.class);

            // Process through SAGA
            customerPaymentSaga.process(paymentResponse);

            log.info("Payment response processed successfully for ride: {}", paymentResponse.getRideRequestId());

        } catch (Exception e) {
            log.error("Error processing payment response: {}", message, e);
        }
    }
}
