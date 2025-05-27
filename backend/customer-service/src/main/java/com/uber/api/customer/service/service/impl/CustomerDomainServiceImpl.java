package com.uber.api.customer.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.customer.service.dto.CallTaxiRequest;
import com.uber.api.customer.service.dto.LocationDTO;
import com.uber.api.customer.service.dto.RideStatusResponse;
import com.uber.api.customer.service.entity.Customer;
import com.uber.api.customer.service.entity.CustomerOutbox;
import com.uber.api.customer.service.repository.CustomerOutboxRepository;
import com.uber.api.customer.service.repository.CustomerRepository;
import com.uber.api.customer.service.repository.QueuedRequestRepository;
import com.uber.api.customer.service.repository.RideRequestRepository;
import com.uber.api.customer.service.service.CustomerDomainService;
import com.uber.api.shared.constants.CustomerStatus;
import com.uber.api.shared.constants.RideStatus;
import com.uber.api.shared.entities.Location;
import com.uber.api.shared.entities.QueuedRequest;
import com.uber.api.shared.entities.RideRequest;
import com.uber.api.shared.events.DriverCompletionEvent;
import com.uber.api.shared.events.PaymentRequestEvent;
import com.uber.api.shared.outbox.OutboxStatus;
import com.uber.api.shared.saga.SagaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerDomainServiceImpl implements CustomerDomainService {

    private final CustomerRepository customerRepository;
    private final RideRequestRepository rideRequestRepository;
    private final CustomerOutboxRepository customerOutboxRepository;
    private final QueuedRequestRepository queuedRequestRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Override
    @Transactional
    public RideStatusResponse callTaxi(CallTaxiRequest request) {
        log.info("=== FALLBACK CALL TAXI - SHOULD USE RideMatchingService ===");
        throw new RuntimeException("Use RideMatchingService.requestRide() instead");
    }

    @Override
    public RideStatusResponse getRideStatus(String customerEmail) {
        log.info("Getting ride status for customer: {}", customerEmail);

        RideRequest currentRide = rideRequestRepository
                .findByCustomerEmailAndStatus(customerEmail, RideStatus.CREATED)
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.PAYMENT_PROCESSING))
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.DRIVER_SEARCHING))
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.DRIVER_ASSIGNED))
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.RIDE_STARTED))
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.CANCELLED))
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.EXPIRED))
                .orElse(null);

        if (currentRide == null) {
            return RideStatusResponse.builder()
                    .customerEmail(customerEmail)
                    .status(RideStatus.RIDE_COMPLETED)
                    .statusMessage("No active ride")
                    .build();
        }

        return mapToRideStatusResponse(currentRide);
    }

    @Override
    @Transactional
    public void cancelRide(String customerEmail) {
        log.info("=== CANCELLING RIDE FOR: {} ===", customerEmail);

        RideRequest activeRide = rideRequestRepository
                .findByCustomerEmailAndStatus(customerEmail, RideStatus.CREATED)
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.PAYMENT_PROCESSING))
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.DRIVER_SEARCHING))
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.DRIVER_ASSIGNED))
                .orElseThrow(() -> new RuntimeException("No cancellable ride found for customer: " + customerEmail));

        // Update ride status to cancelled
        activeRide.setStatus(RideStatus.CANCELLED);
        activeRide.setCompletedAt(ZonedDateTime.now());
        rideRequestRepository.save(activeRide);

        // Reset customer status
        resetCustomerToAvailable(customerEmail);

        // Remove from queue
        removeFromQueue(activeRide.getId());

        // **CRITICAL: Release driver if assigned**
        if (activeRide.getDriverEmail() != null) {
            log.info("🔄 RELEASING DRIVER {} DUE TO CANCELLATION", activeRide.getDriverEmail());
            releaseDriver(activeRide.getDriverEmail(), activeRide.getId(), customerEmail, "CANCELLED");
        }

        // **CRITICAL: Immediately trigger queue processing after cancellation**
        log.info("🔄 TRIGGERING IMMEDIATE QUEUE PROCESSING AFTER CANCELLATION");
        processQueuedRequests();

        log.info("✅ RIDE CANCELLED for customer: {}", customerEmail);
    }

    @Override
    @Transactional
    public void completeRide(String customerEmail) {
        log.info("=== COMPLETING RIDE FOR: {} ===", customerEmail);

        RideRequest activeRide = rideRequestRepository
                .findByCustomerEmailAndStatus(customerEmail, RideStatus.RIDE_STARTED)
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.DRIVER_ASSIGNED))
                .orElseThrow(() -> new RuntimeException("No active ride found for customer: " + customerEmail));

        // Update ride status
        activeRide.setStatus(RideStatus.RIDE_COMPLETED);
        activeRide.setCompletedAt(ZonedDateTime.now());
        rideRequestRepository.save(activeRide);

        // Reset customer status
        resetCustomerToAvailable(customerEmail);

        // **RELEASE DRIVER**
        if (activeRide.getDriverEmail() != null) {
            log.info("🔄 RELEASING DRIVER {} AFTER COMPLETION", activeRide.getDriverEmail());
            releaseDriver(activeRide.getDriverEmail(), activeRide.getId(), customerEmail, "COMPLETED");
        }

        // **CRITICAL: Trigger queue processing**
        log.info("🔄 TRIGGERING IMMEDIATE QUEUE PROCESSING AFTER COMPLETION");
        processQueuedRequests();

        log.info("✅ RIDE COMPLETED for customer: {}", customerEmail);
    }

    @Override
    @Transactional
    public void startRide(String customerEmail) {
        log.info("Starting ride for customer: {}", customerEmail);

        RideRequest assignedRide = rideRequestRepository
                .findByCustomerEmailAndStatus(customerEmail, RideStatus.DRIVER_ASSIGNED)
                .orElseThrow(() -> new RuntimeException("No assigned ride found for customer: " + customerEmail));

        assignedRide.setStatus(RideStatus.RIDE_STARTED);
        rideRequestRepository.save(assignedRide);

        log.info("Ride started successfully for customer: {}", customerEmail);
    }

    @Override
    @Transactional
    public void processExpiredRequests() {
        ZonedDateTime now = ZonedDateTime.now();

        List<QueuedRequest> expiredRequests = queuedRequestRepository
                .findByStatusAndExpiresAtBefore("QUEUED", now);

        log.info("=== PROCESSING {} EXPIRED REQUESTS ===", expiredRequests.size());

        for (QueuedRequest expiredRequest : expiredRequests) {
            try {
                expiredRequest.setStatus("EXPIRED");
                queuedRequestRepository.save(expiredRequest);

                rideRequestRepository.findById(expiredRequest.getRideRequestId()).ifPresent(rideRequest -> {
                    rideRequest.setStatus(RideStatus.EXPIRED);
                    rideRequest.setCompletedAt(now);
                    rideRequestRepository.save(rideRequest);

                    resetCustomerToAvailable(rideRequest.getCustomerEmail());
                    log.info("💀 EXPIRED ride request for customer: {}", rideRequest.getCustomerEmail());
                });

            } catch (Exception e) {
                log.error("Error processing expired request: {}", expiredRequest.getId(), e);
            }
        }
    }

    @Override
    @Transactional
    public void processQueuedRequests() {
        log.info("=== PROCESSING QUEUE (QUEUE BEFORE SAGA) ===");

        // Get queued requests in FIFO order
        List<QueuedRequest> queuedRequests = queuedRequestRepository.findQueuedRequestsOrderedByPriority();

        if (queuedRequests.isEmpty()) {
            log.debug("No queued requests to process");
            return;
        }

        int availableDrivers = getAvailableDriverCount();
        if (availableDrivers == 0) {
            log.info("No available drivers - skipping queue processing");
            return;
        }

        // **STEP 4: Process ONLY the first queued request and START SAGA**
        QueuedRequest firstRequest = queuedRequests.get(0);

        if (!"QUEUED".equals(firstRequest.getStatus())) {
            log.info("First request {} not QUEUED (status: {})",
                    firstRequest.getCustomerEmail(), firstRequest.getStatus());
            return;
        }

        try {
            log.info("🔄 Processing FIRST queued request: {} → STARTING SAGA", firstRequest.getCustomerEmail());

            RideRequest rideRequest = rideRequestRepository.findById(firstRequest.getRideRequestId()).orElse(null);
            if (rideRequest != null && rideRequest.getStatus() == RideStatus.DRIVER_SEARCHING) {

                // **REMOVE FROM QUEUE FIRST**
                queuedRequestRepository.delete(firstRequest);
                log.info("🗑️ Removed {} from queue", firstRequest.getCustomerEmail());

                // **RESET STATUS AND START SAGA**
                rideRequest.setStatus(RideStatus.CREATED);
                rideRequestRepository.save(rideRequest);

                // **START SAGA FOR QUEUED REQUEST**
                startSagaForRide(rideRequest);

                log.info("✅ Started SAGA for queued request: {}", firstRequest.getCustomerEmail());
            }
        } catch (Exception e) {
            log.error("❌ Error processing queued request: {}", firstRequest.getCustomerEmail(), e);
        }

        log.info("=== QUEUE PROCESSING COMPLETE: 1 request processed ===");
    }

    @Override
    public void debugQueueOrder() {
        List<QueuedRequest> queuedRequests = queuedRequestRepository.findQueuedRequestsInFIFOOrder();
        log.info("=== CURRENT QUEUE ORDER ===");
        for (int i = 0; i < queuedRequests.size(); i++) {
            QueuedRequest request = queuedRequests.get(i);
            log.info("Position {}: Customer {} queued at {} (ID: {})",
                    i + 1, request.getCustomerEmail(), request.getQueuedAt(), request.getId());
        }
        log.info("=== END QUEUE ORDER ===");
    }

    @Override
    public RideRequest createRideRequestFromRequest(CallTaxiRequest request) {
        return createRideRequest(request);
    }

    @Override
    public void addToExistingQueue(RideRequest rideRequest) {
        try {
            ZonedDateTime now = ZonedDateTime.now();

            // **SIMPLE QUEUE ENTRY - NO SAGA DATA**
            QueuedRequest queuedRequest = QueuedRequest.builder()
                    .rideRequestId(rideRequest.getId())
                    .sagaId(null) // **NO SAGA ID**
                    .customerEmail(rideRequest.getCustomerEmail())
                    .driverRequestPayload(null) // **NO SAGA PAYLOAD**
                    .queuedAt(now)
                    .expiresAt(now.plusMinutes(10))
                    .status("QUEUED")
                    .build();

            QueuedRequest saved = queuedRequestRepository.save(queuedRequest);
            log.info("✅ ADDED TO QUEUE (NO SAGA): Customer={}, QueuedAt={}",
                    saved.getCustomerEmail(), saved.getQueuedAt());

        } catch (Exception e) {
            log.error("Failed to add to queue", e);
            throw new RuntimeException("Failed to add to queue", e);
        }
    }

    @Override
    public void startSagaForRide(RideRequest rideRequest) {
        try {
            // **INCLUDE LOCATION DATA in payment request**
            PaymentRequestEvent paymentRequestEvent = PaymentRequestEvent.builder()
                    .sagaId(UUID.randomUUID())
                    .rideRequestId(rideRequest.getId())
                    .customerEmail(rideRequest.getCustomerEmail())
                    .amount(rideRequest.getEstimatedPrice())
                    .description("Taxi ride payment")
                    .pickupLocation(rideRequest.getPickupLocation())
                    .destinationLocation(rideRequest.getDestinationLocation())
                    .build();

            saveToOutbox(paymentRequestEvent, paymentRequestEvent.getSagaId(), "payment-requests");
            log.info("✅ Started SAGA for ride: {}", rideRequest.getId());

        } catch (Exception e) {
            log.error("Failed to start SAGA for ride: {}", rideRequest.getId(), e);
            throw new RuntimeException("Failed to start SAGA", e);
        }
    }

    @Override
    public void debugQueueContents() {
        List<QueuedRequest> allRequests = queuedRequestRepository.findAll();
        log.info("=== ALL QUEUED REQUESTS IN DATABASE ===");
        log.info("Total requests in queue table: {}", allRequests.size());

        for (QueuedRequest request : allRequests) {
            log.info("ID: {}, Customer: {}, Status: {}, QueuedAt: {}",
                    request.getId(), request.getCustomerEmail(), request.getStatus(), request.getQueuedAt());
        }
        log.info("=== END ALL REQUESTS ===");

        List<QueuedRequest> queuedOnly = queuedRequestRepository.findQueuedRequestsOrderedByPriority();
        log.info("=== REQUESTS WITH STATUS 'QUEUED' ===");
        log.info("Requests with status 'QUEUED': {}", queuedOnly.size());

        for (QueuedRequest request : queuedOnly) {
            log.info("QUEUED: ID={}, Customer={}, Status={}",
                    request.getId(), request.getCustomerEmail(), request.getStatus());
        }
        log.info("=== END QUEUED ONLY ===");
    }

    @Override
    public int getAvailableDriverCount() {
        try {
            String driverServiceUrl = "http://localhost:4768/api/driver/available-count";
            Integer count = restTemplate.getForObject(driverServiceUrl, Integer.class);
            int result = count != null ? count : 0;
            log.debug("Available drivers: {}", result);
            return result;
        } catch (Exception e) {
            log.error("Failed to check driver availability", e);
            return 0;
        }
    }

    // **HELPER METHODS**

    private void releaseDriver(String driverEmail, UUID rideRequestId, String customerEmail, String status) {
        DriverCompletionEvent driverEvent = DriverCompletionEvent.builder()
                .driverEmail(driverEmail)
                .rideRequestId(rideRequestId)
                .customerEmail(customerEmail)
                .status(status)
                .build();

        // **SAGA INTEGRATION: Use outbox for reliable messaging**
        saveToOutbox(driverEvent, UUID.randomUUID(), "driver-completion");
        log.info("🔄 SENT DRIVER RELEASE EVENT via SAGA for driver: {} status: {}", driverEmail, status);
    }

    private void resetCustomerToAvailable(String customerEmail) {
        customerRepository.findByEmail(customerEmail).ifPresent(customer -> {
            customer.setStatus(CustomerStatus.AVAILABLE);
            customer.setCurrentRideRequestId(null);
            customerRepository.save(customer);
            log.info("Reset customer {} to AVAILABLE", customerEmail);
        });
    }

    private void removeFromQueue(UUID rideRequestId) {
        queuedRequestRepository.findByRideRequestId(rideRequestId).ifPresent(queuedRequest -> {
            queuedRequest.setStatus("CANCELLED");
            queuedRequestRepository.save(queuedRequest);
            log.info("Removed ride {} from queue", rideRequestId);
        });
    }

    private RideRequest createRideRequest(CallTaxiRequest request) {
        Location pickupLocation = mapToLocation(request.getPickupLocation());
        Location destinationLocation = mapToLocation(request.getDestinationLocation());

        RideRequest rideRequest = RideRequest.builder()
                .customerEmail(request.getCustomerEmail())
                .pickupLocation(pickupLocation)
                .destinationLocation(destinationLocation)
                .status(RideStatus.CREATED)
                .createdAt(ZonedDateTime.now())
                .build();

        rideRequest.setEstimatedPrice(rideRequest.calculateEstimatedPrice());
        return rideRequest;
    }

    private Location mapToLocation(LocationDTO locationDTO) {
        return Location.builder()
                .latitude(locationDTO.getLatitude())
                .longitude(locationDTO.getLongitude())
                .address(locationDTO.getAddress())
                .city(locationDTO.getCity())
                .build();
    }

    private RideStatusResponse mapToRideStatusResponse(RideRequest rideRequest) {
        String statusMessage = getStatusMessage(rideRequest.getStatus());

        return RideStatusResponse.builder()
                .rideRequestId(rideRequest.getId())
                .status(rideRequest.getStatus())
                .customerEmail(rideRequest.getCustomerEmail())
                .driverEmail(rideRequest.getDriverEmail())
                .estimatedPrice(rideRequest.getEstimatedPrice())
                .finalPrice(rideRequest.getFinalPrice())
                .createdAt(rideRequest.getCreatedAt())
                .statusMessage(statusMessage)
                .build();
    }

    private String getStatusMessage(RideStatus status) {
        return switch (status) {
            case CREATED -> "Ride request created";
            case PAYMENT_PROCESSING -> "Processing payment...";
            case DRIVER_SEARCHING -> "Looking for available driver. Request will expire in 10 minutes if no driver is found.";
            case DRIVER_ASSIGNED -> "Driver assigned and on the way!";
            case RIDE_STARTED -> "Ride in progress";
            case RIDE_COMPLETED -> "Ride completed";
            case PAYMENT_FAILED -> "Payment failed. Please try again.";
            case DRIVER_UNAVAILABLE -> "No drivers available nearby";
            case CANCELLED -> "Ride cancelled";
            case EXPIRED -> "Request expired after 10 minutes";
            default -> "Unknown status";
        };
    }

    private void saveToOutbox(Object event, UUID sagaId, String eventType) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            CustomerOutbox outboxEvent = CustomerOutbox.builder()
                    .sagaId(sagaId)
                    .eventType(eventType)
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .sagaStatus(SagaStatus.STARTED)
                    .createdAt(ZonedDateTime.now())
                    .build();

            customerOutboxRepository.save(outboxEvent);

        } catch (Exception e) {
            log.error("Failed to save event to outbox", e);
            throw new RuntimeException("Failed to save event to outbox", e);
        }
    }
}
