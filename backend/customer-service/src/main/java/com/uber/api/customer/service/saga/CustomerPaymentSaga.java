package com.uber.api.customer.service.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.customer.service.entity.Customer;
import com.uber.api.customer.service.entity.CustomerOutbox;
import com.uber.api.customer.service.repository.CustomerOutboxRepository;
import com.uber.api.customer.service.repository.CustomerRepository;
import com.uber.api.customer.service.repository.RideRequestRepository;
import com.uber.api.shared.constants.CustomerStatus;
import com.uber.api.shared.constants.RideStatus;
import com.uber.api.shared.entities.RideRequest;
import com.uber.api.shared.events.DriverRequestEvent;
import com.uber.api.shared.events.PaymentRequestEvent;
import com.uber.api.shared.events.PaymentResponseEvent;
import com.uber.api.shared.outbox.OutboxStatus;
import com.uber.api.shared.saga.SagaStatus;
import com.uber.api.shared.saga.SagaStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerPaymentSaga implements SagaStep<PaymentResponseEvent> {

    private final RideRequestRepository rideRequestRepository;
    private final CustomerRepository customerRepository;
    private final CustomerOutboxRepository customerOutboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void process(PaymentResponseEvent paymentResponse) {
        log.info("Processing payment response for ride request: {}", paymentResponse.getRideRequestId());

        try {
            // Find the ride request
            RideRequest rideRequest = findRideRequest(paymentResponse.getRideRequestId());

            if (paymentResponse.getStatus() == com.uber.api.shared.constants.PaymentStatus.COMPLETED) {
                log.info("Payment completed successfully for ride request: {}", paymentResponse.getRideRequestId());

                // Update ride status to driver searching
             
                rideRequestRepository.save(rideRequest);

                // Create driver request event
                DriverRequestEvent driverRequestEvent = DriverRequestEvent.builder()
                        .sagaId(paymentResponse.getSagaId())
                        .rideRequestId(paymentResponse.getRideRequestId())
                        .customerEmail(paymentResponse.getCustomerEmail())
                        .pickupLocation(rideRequest.getPickupLocation())
                        .destinationLocation(rideRequest.getDestinationLocation())
                        .estimatedPrice(rideRequest.getEstimatedPrice())
                        .build();

                // Save to outbox for reliable messaging
                saveToOutbox(driverRequestEvent, paymentResponse.getSagaId(), "driver-requests");

                log.info("Driver request saved to outbox for saga: {}", paymentResponse.getSagaId());

            } else {
                log.error("Payment failed for ride request: {} with reason: {}",
                        paymentResponse.getRideRequestId(), paymentResponse.getFailureReason());
                rollback(paymentResponse);
            }

        } catch (Exception e) {
            log.error("Error processing payment response for ride request: {}",
                    paymentResponse.getRideRequestId(), e);
            rollback(paymentResponse);
        }
    }

    @Override
    @Transactional
    public void rollback(PaymentResponseEvent paymentResponse) {
        log.info("Rolling back payment for ride request: {}", paymentResponse.getRideRequestId());

        try {
            // Find the ride request
            RideRequest rideRequest = findRideRequest(paymentResponse.getRideRequestId());

            // Update ride status to payment failed
            rideRequest.setStatus(RideStatus.PAYMENT_FAILED);
            rideRequestRepository.save(rideRequest);

            // Reset customer status to available
            resetCustomerStatus(paymentResponse.getCustomerEmail());

            log.info("Payment rollback completed for ride request: {}", paymentResponse.getRideRequestId());

        } catch (Exception e) {
            log.error("Error during payment rollback for ride request: {}",
                    paymentResponse.getRideRequestId(), e);
        }
    }

    private RideRequest findRideRequest(UUID rideRequestId) {
        return rideRequestRepository.findById(rideRequestId)
                .orElseThrow(() -> new RuntimeException("Ride request not found for ID: " + rideRequestId));
    }

    private void resetCustomerStatus(String customerEmail) {
        customerRepository.findByEmail(customerEmail).ifPresent(customer -> {
            customer.setStatus(CustomerStatus.AVAILABLE);
            customer.setCurrentRideRequestId(null);
            customerRepository.save(customer);
            log.info("Customer status reset to AVAILABLE for: {}", customerEmail);
        });
    }

    private void saveToOutbox(Object event, UUID sagaId, String eventType) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            CustomerOutbox outboxEvent = CustomerOutbox.builder()
                    .sagaId(sagaId)
                    .eventType(eventType)
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .sagaStatus(SagaStatus.PROCESSING)
                    .createdAt(ZonedDateTime.now())
                    .build();

            customerOutboxRepository.save(outboxEvent);

        } catch (Exception e) {
            log.error("Failed to save event to outbox", e);
            throw new RuntimeException("Failed to save event to outbox", e);
        }
    }
}
