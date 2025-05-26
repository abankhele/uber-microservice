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
import com.uber.api.shared.events.PaymentRefundEvent;
import com.uber.api.shared.outbox.OutboxStatus;
import com.uber.api.shared.saga.SagaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

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

    // Prevent concurrent requests from same customer
    private final ReentrantLock customerLock = new ReentrantLock();

    @Override
    @Transactional
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public RideStatusResponse callTaxi(CallTaxiRequest request) {
        log.info("Processing taxi call request for customer: {}", request.getCustomerEmail());

        // **FIX 1: Prevent concurrent requests from same customer**
        customerLock.lock();
        try {
            // Find or create customer
            Customer customer = findOrCreateCustomer(request.getCustomerEmail());

            // **FIX 2: Strict validation to prevent duplicate requests**
            validateCustomerCanCallTaxi(customer);

            // **FIX 3: Real-time driver availability check**
            int availableDrivers = getAvailableDriverCount();
            log.info("Available drivers: {} for customer: {}", availableDrivers, request.getCustomerEmail());

            // Create ride request
            RideRequest rideRequest = createRideRequest(request);
            RideRequest savedRideRequest = rideRequestRepository.save(rideRequest);

            // Update customer status atomically
            customer.setStatus(CustomerStatus.REQUESTING);
            customer.setCurrentRideRequestId(savedRideRequest.getId());
            customerRepository.save(customer);

            if (availableDrivers == 0) {
                // **FIX 4: Queue system with 10-minute timeout**
                log.warn("No drivers available for customer: {}. Adding to queue with 10-minute timeout.",
                        request.getCustomerEmail());

                savedRideRequest.setStatus(RideStatus.DRIVER_SEARCHING);
                rideRequestRepository.save(savedRideRequest);

                // Queue the request with timeout
                queueRideRequestWithTimeout(savedRideRequest, request);

                return RideStatusResponse.builder()
                        .rideRequestId(savedRideRequest.getId())
                        .status(RideStatus.DRIVER_SEARCHING)
                        .customerEmail(request.getCustomerEmail())
                        .estimatedPrice(savedRideRequest.getEstimatedPrice())
                        .createdAt(savedRideRequest.getCreatedAt())
                        .statusMessage("All drivers are busy. You're in queue for the next available driver. Request will expire in 10 minutes if no driver is found.")
                        .build();
            }

            // **FIX 5: Immediate processing when drivers available**
            PaymentRequestEvent paymentRequestEvent = PaymentRequestEvent.builder()
                    .sagaId(UUID.randomUUID())
                    .rideRequestId(savedRideRequest.getId())
                    .customerEmail(request.getCustomerEmail())
                    .amount(savedRideRequest.getEstimatedPrice())
                    .description("Taxi ride payment")
                    .build();

            saveToOutbox(paymentRequestEvent, paymentRequestEvent.getSagaId(), "payment-requests");

            log.info("Taxi call request processed successfully for customer: {}", request.getCustomerEmail());
            return mapToRideStatusResponse(savedRideRequest);

        } finally {
            customerLock.unlock();
        }
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
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.REFUND_PROCESSING))
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
        log.info("Cancelling ride for customer: {}", customerEmail);

        // **FIX 6: Comprehensive cancellation handling**
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

        // Remove from queue if queued
        removeFromQueue(activeRide.getId());

        // **FIX 7: Handle refunds for paid rides**
        if (activeRide.getStatus() == RideStatus.PAYMENT_PROCESSING ||
                activeRide.getDriverEmail() != null) {

            initiateRefund(activeRide);
        }

        // **FIX 8: Reset driver if assigned**
        if (activeRide.getDriverEmail() != null) {
            releaseDriver(activeRide.getDriverEmail(), activeRide.getId(), customerEmail, "CANCELLED");
        }

        log.info("Ride cancelled successfully for customer: {}", customerEmail);
    }

    @Override
    @Transactional
    public void completeRide(String customerEmail) {
        log.info("Completing ride for customer: {}", customerEmail);

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

        // **FIX 9: Always release driver after completion**
        if (activeRide.getDriverEmail() != null) {
            releaseDriver(activeRide.getDriverEmail(), activeRide.getId(), customerEmail, "COMPLETED");
        }

        log.info("Ride completed successfully for customer: {}", customerEmail);
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

        // **FIX 10: Handle expired queued requests with refunds**
        List<QueuedRequest> expiredRequests = queuedRequestRepository
                .findByStatusAndExpiresAtBefore("QUEUED", now);

        log.info("Processing {} expired ride requests", expiredRequests.size());

        for (QueuedRequest expiredRequest : expiredRequests) {
            try {
                expiredRequest.setStatus("EXPIRED");
                queuedRequestRepository.save(expiredRequest);

                rideRequestRepository.findById(expiredRequest.getRideRequestId()).ifPresent(rideRequest -> {
                    rideRequest.setStatus(RideStatus.EXPIRED);
                    rideRequest.setCompletedAt(now);
                    rideRequestRepository.save(rideRequest);

                    // **FIX 11: Initiate refund for expired requests**
                    initiateRefund(rideRequest);

                    // Reset customer status
                    resetCustomerToAvailable(rideRequest.getCustomerEmail());

                    log.info("Expired and refunded ride request for customer: {}", rideRequest.getCustomerEmail());
                });

            } catch (Exception e) {
                log.error("Error processing expired request: {}", expiredRequest.getId(), e);
            }
        }
    }

    @Override
    @Transactional
    public void processQueuedRequests() {
        // **FIX 12: Smart queue processing with driver availability**
        int availableDrivers = getAvailableDriverCount();

        if (availableDrivers == 0) {
            log.debug("No available drivers - skipping queue processing");
            return;
        }

        List<QueuedRequest> queuedRequests = queuedRequestRepository.findQueuedRequestsOrderedByPriority();

        if (queuedRequests.isEmpty()) {
            return;
        }

        log.info("Processing {} queued requests with {} available drivers",
                queuedRequests.size(), availableDrivers);

        int processedCount = 0;
        for (QueuedRequest queuedRequest : queuedRequests) {
            if (processedCount >= availableDrivers) {
                log.info("Processed {} requests, no more drivers available", processedCount);
                break;
            }

            try {
                // Check if request has expired
                if (queuedRequest.getExpiresAt().isBefore(ZonedDateTime.now())) {
                    handleExpiredQueuedRequest(queuedRequest);
                    continue;
                }

                // Mark as processing
                queuedRequest.setStatus("PROCESSING");
                queuedRequestRepository.save(queuedRequest);

                // Process the queued request
                if (processQueuedRequest(queuedRequest)) {
                    processedCount++;
                    queuedRequest.setStatus("COMPLETED");
                    queuedRequestRepository.save(queuedRequest);
                    log.info("Successfully processed queued request for ride: {}", queuedRequest.getRideRequestId());
                } else {
                    // Reset to queued if processing failed
                    queuedRequest.setStatus("QUEUED");
                    queuedRequestRepository.save(queuedRequest);
                }

            } catch (Exception e) {
                log.error("Error processing queued request: {}", queuedRequest.getId(), e);
                queuedRequest.setStatus("QUEUED"); // Retry later
                queuedRequestRepository.save(queuedRequest);
            }
        }
    }

    // **HELPER METHODS**

    private int getAvailableDriverCount() {
        try {
            String driverServiceUrl = "http://localhost:4768/api/driver/available-count";
            Integer count = restTemplate.getForObject(driverServiceUrl, Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Failed to check driver availability", e);
            return 0; // Fail safe
        }
    }

    private void queueRideRequestWithTimeout(RideRequest rideRequest, CallTaxiRequest request) {
        try {
            UUID sagaId = UUID.randomUUID();
            ZonedDateTime now = ZonedDateTime.now();

            QueuedRequest queuedRequest = QueuedRequest.builder()
                    .rideRequestId(rideRequest.getId())
                    .sagaId(sagaId)
                    .customerEmail(request.getCustomerEmail())
                    .driverRequestPayload(createDriverRequestPayload(rideRequest, sagaId))
                    .queuedAt(now)
                    .expiresAt(now.plusMinutes(10)) // **10-minute timeout**
                    .priority(1)
                    .status("QUEUED")
                    .build();

            queuedRequestRepository.save(queuedRequest);
            log.info("Queued ride request for customer: {} with 10-minute timeout", request.getCustomerEmail());

        } catch (Exception e) {
            log.error("Failed to queue ride request", e);
            throw new RuntimeException("Failed to queue ride request", e);
        }
    }

    private String createDriverRequestPayload(RideRequest rideRequest, UUID sagaId) throws Exception {
        // This will be used when processing from queue
        PaymentRequestEvent paymentEvent = PaymentRequestEvent.builder()
                .sagaId(sagaId)
                .rideRequestId(rideRequest.getId())
                .customerEmail(rideRequest.getCustomerEmail())
                .amount(rideRequest.getEstimatedPrice())
                .description("Taxi ride payment")
                .build();

        return objectMapper.writeValueAsString(paymentEvent);
    }

    private boolean processQueuedRequest(QueuedRequest queuedRequest) {
        try {
            // Deserialize and process payment request
            PaymentRequestEvent paymentEvent = objectMapper.readValue(
                    queuedRequest.getDriverRequestPayload(), PaymentRequestEvent.class);

            saveToOutbox(paymentEvent, queuedRequest.getSagaId(), "payment-requests");
            return true;

        } catch (Exception e) {
            log.error("Failed to process queued request", e);
            return false;
        }
    }

    private void handleExpiredQueuedRequest(QueuedRequest queuedRequest) {
        queuedRequest.setStatus("EXPIRED");
        queuedRequestRepository.save(queuedRequest);

        rideRequestRepository.findById(queuedRequest.getRideRequestId()).ifPresent(rideRequest -> {
            rideRequest.setStatus(RideStatus.EXPIRED);
            rideRequest.setCompletedAt(ZonedDateTime.now());
            rideRequestRepository.save(rideRequest);

            resetCustomerToAvailable(rideRequest.getCustomerEmail());
            log.info("Expired queued request for customer: {}", rideRequest.getCustomerEmail());
        });
    }

    private void initiateRefund(RideRequest rideRequest) {
        try {
            rideRequest.setStatus(RideStatus.REFUND_PROCESSING);
            rideRequestRepository.save(rideRequest);

            PaymentRefundEvent refundEvent = PaymentRefundEvent.builder()
                    .sagaId(UUID.randomUUID())
                    .rideRequestId(rideRequest.getId())
                    .customerEmail(rideRequest.getCustomerEmail())
                    .amount(rideRequest.getEstimatedPrice())
                    .reason("Ride cancelled/expired")
                    .build();

            saveToOutbox(refundEvent, refundEvent.getSagaId(), "payment-refunds");
            log.info("Initiated refund for ride: {}", rideRequest.getId());

        } catch (Exception e) {
            log.error("Failed to initiate refund for ride: {}", rideRequest.getId(), e);
        }
    }

    private void releaseDriver(String driverEmail, UUID rideRequestId, String customerEmail, String status) {
        DriverCompletionEvent driverEvent = DriverCompletionEvent.builder()
                .driverEmail(driverEmail)
                .rideRequestId(rideRequestId)
                .customerEmail(customerEmail)
                .status(status)
                .build();

        saveToOutbox(driverEvent, UUID.randomUUID(), "driver-completion");
        log.info("Released driver {} for ride completion: {}", driverEmail, status);
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

        // **Additional validation: Check for any active rides**
        boolean hasActiveRide = rideRequestRepository
                .findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.CREATED).isPresent() ||
                rideRequestRepository
                        .findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.PAYMENT_PROCESSING).isPresent() ||
                rideRequestRepository
                        .findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.DRIVER_SEARCHING).isPresent() ||
                rideRequestRepository
                        .findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.DRIVER_ASSIGNED).isPresent() ||
                rideRequestRepository
                        .findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.RIDE_STARTED).isPresent();

        if (hasActiveRide) {
            throw new RuntimeException("Customer already has an active ride request");
        }
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
            case DRIVER_SEARCHING -> "All drivers are busy. You're in queue for the next available driver.";
            case DRIVER_ASSIGNED -> "Driver assigned and on the way!";
            case RIDE_STARTED -> "Ride in progress";
            case RIDE_COMPLETED -> "Ride completed";
            case PAYMENT_FAILED -> "Payment failed. Please try again.";
            case DRIVER_UNAVAILABLE -> "No drivers available nearby";
            case CANCELLED -> "Ride cancelled";
            case EXPIRED -> "Request expired after 10 minutes";
            case REFUND_PROCESSING -> "Processing refund...";
            case REFUND_COMPLETED -> "Refund completed";
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
