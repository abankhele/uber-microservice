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
        log.info("=== CREATING RIDE REQUEST FOR: {} ===", request.getCustomerEmail());

        try {
            // Find or create customer
            Customer customer = findOrCreateCustomer(request.getCustomerEmail());
            validateCustomerCanCallTaxi(customer);

            // **ALWAYS CREATE RIDE REQUEST - NO MATTER WHAT**
            RideRequest rideRequest = createRideRequest(request);
            RideRequest savedRideRequest = rideRequestRepository.save(rideRequest);

            // Update customer status
            customer.setStatus(CustomerStatus.REQUESTING);
            customer.setCurrentRideRequestId(savedRideRequest.getId());
            customerRepository.save(customer);

            // **ALWAYS ADD TO QUEUE WITH 10-MINUTE TIMEOUT**
            addToQueue(savedRideRequest);

            log.info("✅ RIDE REQUEST CREATED AND QUEUED: {} for customer: {}",
                    savedRideRequest.getId(), request.getCustomerEmail());

            return RideStatusResponse.builder()
                    .rideRequestId(savedRideRequest.getId())
                    .status(RideStatus.DRIVER_SEARCHING)
                    .customerEmail(request.getCustomerEmail())
                    .estimatedPrice(savedRideRequest.getEstimatedPrice())
                    .createdAt(savedRideRequest.getCreatedAt())
                    .statusMessage("Looking for available driver. Request will expire in 10 minutes if no driver is found.")
                    .build();

        } catch (Exception e) {
            log.error("❌ ERROR creating ride request for {}: {}", request.getCustomerEmail(), e.getMessage());
            throw new RuntimeException("Failed to create ride request: " + e.getMessage());
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

        // **ISSUE REFUND IF PAYMENT WAS PROCESSED**
        if (activeRide.getStatus() == RideStatus.PAYMENT_PROCESSING ||
                activeRide.getDriverEmail() != null) {
            issueRefund(activeRide, "Ride cancelled by customer");
        }

        // Release driver if assigned
        if (activeRide.getDriverEmail() != null) {
            releaseDriver(activeRide.getDriverEmail(), activeRide.getId(), customerEmail, "CANCELLED");
        }

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

        // Release driver
        if (activeRide.getDriverEmail() != null) {
            releaseDriver(activeRide.getDriverEmail(), activeRide.getId(), customerEmail, "COMPLETED");
        }

        // **CRITICAL FIX: Immediately trigger queue processing**
        log.info("🔄 TRIGGERING IMMEDIATE QUEUE PROCESSING after ride completion");
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

        // **FIND EXPIRED QUEUED REQUESTS**
        List<QueuedRequest> expiredRequests = queuedRequestRepository
                .findByStatusAndExpiresAtBefore("QUEUED", now);

        log.info("=== PROCESSING {} EXPIRED REQUESTS ===", expiredRequests.size());

        for (QueuedRequest expiredRequest : expiredRequests) {
            try {
                // Mark queue request as expired
                expiredRequest.setStatus("EXPIRED");
                queuedRequestRepository.save(expiredRequest);

                // Update ride request
                rideRequestRepository.findById(expiredRequest.getRideRequestId()).ifPresent(rideRequest -> {
                    rideRequest.setStatus(RideStatus.EXPIRED);
                    rideRequest.setCompletedAt(now);
                    rideRequestRepository.save(rideRequest);

                    // **ISSUE REFUND FOR EXPIRED REQUESTS**
                    issueRefund(rideRequest, "Request expired after 10 minutes");

                    // Reset customer status
                    resetCustomerToAvailable(rideRequest.getCustomerEmail());

                    log.info("💀 EXPIRED and REFUNDED ride request for customer: {}", rideRequest.getCustomerEmail());
                });

            } catch (Exception e) {
                log.error("Error processing expired request: {}", expiredRequest.getId(), e);
            }
        }
    }

    @Override
    @Transactional
    public void processQueuedRequests() {
        log.info("=== PROCESSING QUEUE ===");

        // **CRITICAL FIX: Check available drivers first**
        int availableDrivers = getAvailableDriverCount();
        log.info("Available drivers for queue processing: {}", availableDrivers);

        if (availableDrivers == 0) {
            log.info("No available drivers - skipping queue processing");
            return;
        }

        // **CRITICAL FIX: Get queued requests in TRUE FIFO order**
        List<QueuedRequest> queuedRequests = queuedRequestRepository.findQueuedRequestsOrderedByPriority();
        log.info("Found {} queued requests to process", queuedRequests.size());

        if (queuedRequests.isEmpty()) {
            log.info("No queued requests to process");
            return;
        }

        // **CRITICAL FIX: Process ONE request at a time to prevent race conditions**
        int processedCount = 0;
        for (QueuedRequest queuedRequest : queuedRequests) {
            if (processedCount >= availableDrivers) {
                log.info("Processed {} requests, no more drivers available", processedCount);
                break;
            }

            try {
                log.info("🔄 Processing queued request: {} for customer: {} (queued at: {})",
                        queuedRequest.getId(), queuedRequest.getCustomerEmail(), queuedRequest.getQueuedAt());

                // Check if expired
                if (queuedRequest.getExpiresAt().isBefore(ZonedDateTime.now())) {
                    log.warn("Request {} has expired, skipping", queuedRequest.getId());
                    handleExpiredQueuedRequest(queuedRequest);
                    continue;
                }

                // **CRITICAL FIX: Mark as processing to prevent duplicate processing**
                queuedRequest.setStatus("PROCESSING");
                queuedRequestRepository.save(queuedRequest);

                // **Process the payment for queued request**
                if (processQueuedPayment(queuedRequest)) {
                    queuedRequest.setStatus("COMPLETED");
                    queuedRequestRepository.save(queuedRequest);
                    processedCount++;
                    log.info("✅ Successfully processed queued request: {} (order: {})",
                            queuedRequest.getId(), processedCount);

                    // **CRITICAL FIX: Update available driver count after processing**
                    availableDrivers = getAvailableDriverCount();
                    log.info("Remaining available drivers: {}", availableDrivers);
                } else {
                    // Reset to queued if processing failed
                    queuedRequest.setStatus("QUEUED");
                    queuedRequestRepository.save(queuedRequest);
                    log.error("❌ Failed to process queued request: {}", queuedRequest.getId());
                }

            } catch (Exception e) {
                log.error("Error processing queued request: {}", queuedRequest.getId(), e);
                // Reset to queued for retry
                queuedRequest.setStatus("QUEUED");
                queuedRequestRepository.save(queuedRequest);
            }
        }

        log.info("=== QUEUE PROCESSING COMPLETE: {} requests processed ===", processedCount);
    }



    private void addToQueue(RideRequest rideRequest) {
        try {
            UUID sagaId = UUID.randomUUID();
            ZonedDateTime now = ZonedDateTime.now();

            // **SET RIDE STATUS TO DRIVER_SEARCHING**
            rideRequest.setStatus(RideStatus.DRIVER_SEARCHING);
            rideRequestRepository.save(rideRequest);

            // **CREATE PAYMENT EVENT FOR LATER PROCESSING**
            PaymentRequestEvent paymentEvent = PaymentRequestEvent.builder()
                    .sagaId(sagaId)
                    .rideRequestId(rideRequest.getId())
                    .customerEmail(rideRequest.getCustomerEmail())
                    .amount(rideRequest.getEstimatedPrice())
                    .description("Taxi ride payment")
                    .build();

            String payload = objectMapper.writeValueAsString(paymentEvent);

            // **CRITICAL FIX: Use queuedAt for ordering, remove priority**
            QueuedRequest queuedRequest = QueuedRequest.builder()
                    .rideRequestId(rideRequest.getId())
                    .sagaId(sagaId)
                    .customerEmail(rideRequest.getCustomerEmail())
                    .driverRequestPayload(payload)
                    .queuedAt(now) // **THIS DETERMINES ORDER**
                    .expiresAt(now.plusMinutes(10))
                    .status("QUEUED")
                    .build();

            queuedRequestRepository.save(queuedRequest);
            log.info("🔄 ADDED TO QUEUE: {} for customer: {} at time: {}",
                    rideRequest.getId(), rideRequest.getCustomerEmail(), now);

        } catch (Exception e) {
            log.error("Failed to add to queue", e);
            throw new RuntimeException("Failed to add to queue", e);
        }
    }


    private boolean processQueuedPayment(QueuedRequest queuedRequest) {
        try {
            PaymentRequestEvent paymentEvent = objectMapper.readValue(
                    queuedRequest.getDriverRequestPayload(), PaymentRequestEvent.class);

            saveToOutbox(paymentEvent, queuedRequest.getSagaId(), "payment-requests");
            log.info("Payment request sent for queued ride: {}", queuedRequest.getRideRequestId());
            return true;

        } catch (Exception e) {
            log.error("Failed to process queued payment", e);
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

            issueRefund(rideRequest, "Request expired after 10 minutes");
            resetCustomerToAvailable(rideRequest.getCustomerEmail());
        });
    }

    private void issueRefund(RideRequest rideRequest, String reason) {
        try {
            PaymentRefundEvent refundEvent = PaymentRefundEvent.builder()
                    .sagaId(UUID.randomUUID())
                    .rideRequestId(rideRequest.getId())
                    .customerEmail(rideRequest.getCustomerEmail())
                    .amount(rideRequest.getEstimatedPrice())
                    .reason(reason)
                    .build();

            saveToOutbox(refundEvent, refundEvent.getSagaId(), "payment-refunds");
            log.info("💰 REFUND ISSUED for customer: {} amount: {} reason: {}",
                    rideRequest.getCustomerEmail(), rideRequest.getEstimatedPrice(), reason);

        } catch (Exception e) {
            log.error("Failed to issue refund", e);
        }
    }

    private int getAvailableDriverCount() {
        try {
            String driverServiceUrl = "http://localhost:4768/api/driver/available-count";
            Integer count = restTemplate.getForObject(driverServiceUrl, Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("Failed to check driver availability", e);
            return 0;
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
        log.info("🔄 RELEASED driver {} for ride completion: {}", driverEmail, status);
    }

    private void resetCustomerToAvailable(String customerEmail) {
        customerRepository.findByEmail(customerEmail).ifPresent(customer -> {
            customer.setStatus(CustomerStatus.AVAILABLE);
            customer.setCurrentRideRequestId(null);
            customerRepository.save(customer);
        });
    }

    private void removeFromQueue(UUID rideRequestId) {
        queuedRequestRepository.findByRideRequestId(rideRequestId).ifPresent(queuedRequest -> {
            queuedRequest.setStatus("CANCELLED");
            queuedRequestRepository.save(queuedRequest);
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

        boolean hasActiveRide = rideRequestRepository
                .findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.CREATED).isPresent() ||
                rideRequestRepository.findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.PAYMENT_PROCESSING).isPresent() ||
                rideRequestRepository.findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.DRIVER_SEARCHING).isPresent() ||
                rideRequestRepository.findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.DRIVER_ASSIGNED).isPresent() ||
                rideRequestRepository.findByCustomerEmailAndStatus(customer.getEmail(), RideStatus.RIDE_STARTED).isPresent();

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
            case DRIVER_SEARCHING -> "Looking for available driver. Request will expire in 10 minutes if no driver is found.";
            case DRIVER_ASSIGNED -> "Driver assigned and on the way!";
            case RIDE_STARTED -> "Ride in progress";
            case RIDE_COMPLETED -> "Ride completed";
            case PAYMENT_FAILED -> "Payment failed. Please try again.";
            case DRIVER_UNAVAILABLE -> "No drivers available nearby";
            case CANCELLED -> "Ride cancelled";
            case EXPIRED -> "Request expired after 10 minutes - refund issued";
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
