package com.uber.api.customer.service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.customer.service.dto.CallTaxiRequest;
import com.uber.api.customer.service.dto.RideStatusResponse;
import com.uber.api.customer.service.entity.Customer;
import com.uber.api.customer.service.repository.CustomerRepository;
import com.uber.api.customer.service.repository.QueuedRequestRepository;
import com.uber.api.customer.service.repository.RideRequestRepository;
import com.uber.api.shared.constants.CustomerStatus;
import com.uber.api.shared.constants.RideStatus;
import com.uber.api.shared.entities.QueuedRequest;
import com.uber.api.shared.entities.RideRequest;
import com.uber.api.shared.events.PaymentRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RideMatchingService {

    private final CustomerDomainService customerDomainService;
    private final CustomerRepository customerRepository;
    private final RideRequestRepository rideRequestRepository;
    private final QueuedRequestRepository queuedRequestRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public RideStatusResponse requestRide(CallTaxiRequest request) {
        log.info("=== SIMPLE RIDE REQUEST FOR: {} ===", request.getCustomerEmail());

        try {
            // Find or create customer
            Customer customer = findOrCreateCustomer(request.getCustomerEmail());
            validateCustomerCanCallTaxi(customer);

            // Create ride request
            RideRequest rideRequest = customerDomainService.createRideRequestFromRequest(request);
            RideRequest savedRideRequest = rideRequestRepository.save(rideRequest);

            // Update customer status
            customer.setStatus(CustomerStatus.REQUESTING);
            customer.setCurrentRideRequestId(savedRideRequest.getId());
            customerRepository.save(customer);

            // **CRITICAL DEBUG: Add detailed logging**
            log.info("🔄 ABOUT TO ADD TO QUEUE for: {}", request.getCustomerEmail());

            savedRideRequest.setStatus(RideStatus.DRIVER_SEARCHING);
            rideRequestRepository.save(savedRideRequest);

            // **DIRECT QUEUE ADDITION - BYPASS addToExistingQueue**
            try {
                log.info("🔄 STARTING DIRECT QUEUE ADDITION for: {}", request.getCustomerEmail());

                UUID sagaId = UUID.randomUUID();
                ZonedDateTime now = ZonedDateTime.now();

                PaymentRequestEvent paymentEvent = PaymentRequestEvent.builder()
                        .sagaId(sagaId)
                        .rideRequestId(savedRideRequest.getId())
                        .customerEmail(savedRideRequest.getCustomerEmail())
                        .amount(savedRideRequest.getEstimatedPrice())
                        .description("Taxi ride payment")
                        .build();

                String payload = objectMapper.writeValueAsString(paymentEvent);
                log.info("✅ Created payment event payload for: {}", request.getCustomerEmail());

                QueuedRequest queuedRequest = QueuedRequest.builder()
                        .rideRequestId(savedRideRequest.getId())
                        .sagaId(sagaId)
                        .customerEmail(savedRideRequest.getCustomerEmail())
                        .driverRequestPayload(payload)
                        .queuedAt(now)
                        .expiresAt(now.plusMinutes(10))
                        .status("QUEUED")
                        .build();

                log.info("🔄 About to save QueuedRequest to database for: {}", request.getCustomerEmail());
                QueuedRequest savedQueuedRequest = queuedRequestRepository.save(queuedRequest);
                log.info("✅ SUCCESSFULLY SAVED TO QUEUE: ID={}, Customer={}, Status={}",
                        savedQueuedRequest.getId(), savedQueuedRequest.getCustomerEmail(), savedQueuedRequest.getStatus());

            } catch (Exception e) {
                log.error("❌ FAILED TO DIRECTLY ADD TO QUEUE for: {}", request.getCustomerEmail(), e);
                throw new RuntimeException("Failed to add to queue", e);
            }

            return RideStatusResponse.builder()
                    .rideRequestId(savedRideRequest.getId())
                    .status(RideStatus.DRIVER_SEARCHING)
                    .customerEmail(request.getCustomerEmail())
                    .estimatedPrice(savedRideRequest.getEstimatedPrice())
                    .createdAt(savedRideRequest.getCreatedAt())
                    .statusMessage("Looking for available driver. Request will expire in 10 minutes if no driver is found.")
                    .build();

        } catch (Exception e) {
            log.error("❌ ERROR processing ride request for {}: {}", request.getCustomerEmail(), e.getMessage());
            throw new RuntimeException("Failed to process ride request: " + e.getMessage());
        }
    }

    public void onDriverAvailable() {
        log.info("🔄 Driver became available - triggering queue processing");
        customerDomainService.processQueuedRequests();
    }

    private Customer findOrCreateCustomer(String email) {
        return customerRepository.findByEmail(email)
                .orElseGet(() -> {
                    Customer newCustomer = Customer.builder()
                            .email(email)
                            .name("Customer")
                            .status(CustomerStatus.AVAILABLE)
                            .build();
                    return customerRepository.save(newCustomer);
                });
    }

    private void validateCustomerCanCallTaxi(Customer customer) {
        if (customer.getStatus() != CustomerStatus.AVAILABLE) {
            throw new RuntimeException("Customer is not available for new rides. Current status: " + customer.getStatus());
        }
    }
}
