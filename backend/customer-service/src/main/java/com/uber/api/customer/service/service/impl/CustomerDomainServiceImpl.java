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
import com.uber.api.shared.events.DriverRequestEvent;
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
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    @Transactional
    public RideStatusResponse callTaxi(CallTaxiRequest request) {
        log.info("Processing taxi call request for customer: {}", request.getCustomerEmail());

        // Find or create customer
        Customer customer = findOrCreateCustomer(request.getCustomerEmail());

        // Validate customer can make a new request
        validateCustomerCanCallTaxi(customer);

        // Check driver availability BEFORE creating ride request
        boolean driversAvailable = checkDriverAvailability();

        // Create ride request
        RideRequest rideRequest = createRideRequest(request);
        RideRequest savedRideRequest = rideRequestRepository.save(rideRequest);

        // Update customer status
        customer.setStatus(CustomerStatus.REQUESTING);
        customer.setCurrentRideRequestId(savedRideRequest.getId());
        customerRepository.save(customer);

        if (!driversAvailable) {
            // No drivers available - queue the request and set status to DRIVER_SEARCHING
            log.warn("No drivers available for customer: {}. Queueing request.", request.getCustomerEmail());

            savedRideRequest.setStatus(RideStatus.DRIVER_SEARCHING);
            rideRequestRepository.save(savedRideRequest);

            // Queue the payment and driver request for later processing
            queueRideRequest(savedRideRequest, request);

            return RideStatusResponse.builder()
                    .rideRequestId(savedRideRequest.getId())
                    .status(RideStatus.DRIVER_SEARCHING)
                    .customerEmail(request.getCustomerEmail())
                    .estimatedPrice(savedRideRequest.getEstimatedPrice())
                    .createdAt(savedRideRequest.getCreatedAt())
                    .statusMessage("All drivers are busy. You're in queue for the next available driver. Request will expire in 10 minutes.")
                    .build();
        }

        // Drivers available - process payment immediately
        PaymentRequestEvent paymentRequestEvent = PaymentRequestEvent.builder()
                .sagaId(UUID.randomUUID())
                .rideRequestId(savedRideRequest.getId())
                .customerEmail(request.getCustomerEmail())
                .amount(savedRideRequest.getEstimatedPrice())
                .description("Taxi ride payment")
                .build();

        // Save to outbox for reliable messaging
        saveToOutbox(paymentRequestEvent, paymentRequestEvent.getSagaId(), "payment-requests");

        log.info("Taxi call request processed successfully for customer: {}", request.getCustomerEmail());

        return mapToRideStatusResponse(savedRideRequest);
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
        log.info("Cancelling ride for customer: {}", customerEmail);

        // Find active ride (not started yet)
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
        Customer customer = customerRepository.findByEmail(customerEmail)
                .orElseThrow(() -> new RuntimeException("Customer not found: " + customerEmail));

        customer.setStatus(CustomerStatus.AVAILABLE);
        customer.setCurrentRideRequestId(null);
        customerRepository.save(customer);

        // Remove from queue if queued
        queuedRequestRepository.findByRideRequestId(activeRide.getId()).ifPresent(queuedRequest -> {
            queuedRequest.setStatus("CANCELLED");
            queuedRequestRepository.save(queuedRequest);
            log.info("Removed cancelled ride from queue: {}", activeRide.getId());
        });

        // If driver was assigned, reset driver status
        if (activeRide.getDriverEmail() != null) {
            DriverCompletionEvent driverEvent = DriverCompletionEvent.builder()
                    .driverEmail(activeRide.getDriverEmail())
                    .rideRequestId(activeRide.getId())
                    .customerEmail(customerEmail)
                    .status("CANCELLED")
                    .build();

            saveToOutbox(driverEvent, UUID.randomUUID(), "driver-completion");
            log.info("Driver completion event sent for cancelled ride: {}", activeRide.getDriverEmail());
        }

        log.info("Ride cancelled successfully for customer: {}", customerEmail);
    }

    @Override
    @Transactional
    public void completeRide(String customerEmail) {
        log.info("Completing ride for customer: {}", customerEmail);

        // Find active ride (either DRIVER_ASSIGNED or RIDE_STARTED)
        RideRequest activeRide = rideRequestRepository
                .findByCustomerEmailAndStatus(customerEmail, RideStatus.RIDE_STARTED)
                .or(() -> rideRequestRepository.findByCustomerEmailAndStatus(customerEmail, RideStatus.DRIVER_ASSIGNED))
                .orElseThrow(() -> new RuntimeException("No active ride found for customer: " + customerEmail));

        // Update ride status
        activeRide.setStatus(RideStatus.RIDE_COMPLETED);
        activeRide.setCompletedAt(ZonedDateTime.now());
        rideRequestRepository.save(activeRide);

        // Reset customer status
        Customer customer = customerRepository.findByEmail(customerEmail)
                .orElseThrow(() -> new RuntimeException("Customer not found: " + customerEmail));

        customer.setStatus(CustomerStatus.AVAILABLE);
        customer.setCurrentRideRequestId(null);
        customerRepository.save(customer);

        // Reset driver status via event
        if (activeRide.getDriverEmail() != null) {
            DriverCompletionEvent driverEvent = DriverCompletionEvent.builder()
                    .driverEmail(activeRide.getDriverEmail())
                    .rideRequestId(activeRide.getId())
                    .customerEmail(customerEmail)
                    .status("COMPLETED")
                    .build();

            saveToOutbox(driverEvent, UUID.randomUUID(), "driver-completion");
            log.info("Driver completion event sent for driver: {}", activeRide.getDriverEmail());
        }

        log.info("Ride completed successfully for customer: {}", customerEmail);
    }

    @Override
    @Transactional
    public void startRide(String customerEmail) {
        log.info("Starting ride for customer: {}", customerEmail);

        // Find ride with DRIVER_ASSIGNED status
        RideRequest assignedRide = rideRequestRepository
                .findByCustomerEmailAndStatus(customerEmail, RideStatus.DRIVER_ASSIGNED)
                .orElseThrow(() -> new RuntimeException("No assigned ride found for customer: " + customerEmail));

        // Update ride status to RIDE_STARTED
        assignedRide.setStatus(RideStatus.RIDE_STARTED);
        rideRequestRepository.save(assignedRide);

        log.info("Ride started successfully for customer: {}", customerEmail);
    }

    @Override
    @Transactional
    public void processExpiredRequests() {
        ZonedDateTime now = ZonedDateTime.now();

        // Find expired queued requests
        List<QueuedRequest> expiredRequests = queuedRequestRepository
                .findByStatusAndExpiresAtBefore("QUEUED", now);

        log.info("Processing {} expired ride requests", expiredRequests.size());

        for (QueuedRequest expiredRequest : expiredRequests) {
            try {
                // Update queued request status
                expiredRequest.setStatus("EXPIRED");
                queuedRequestRepository.save(expiredRequest);

                // Update ride request status
                rideRequestRepository.findById(expiredRequest.getRideRequestId()).ifPresent(rideRequest -> {
                    rideRequest.setStatus(RideStatus.EXPIRED);
                    rideRequest.setCompletedAt(now);
                    rideRequestRepository.save(rideRequest);

                    // Reset customer status
                    customerRepository.findByEmail(rideRequest.getCustomerEmail()).ifPresent(customer -> {
                        customer.setStatus(CustomerStatus.AVAILABLE);
                        customer.setCurrentRideRequestId(null);
                        customerRepository.save(customer);
                    });

                    log.info("Expired ride request for customer: {}", rideRequest.getCustomerEmail());
                });

            } catch (Exception e) {
                log.error("Error processing expired request: {}", expiredRequest.getId(), e);
            }
        }
    }

    @Override
    @Transactional
    public void processQueuedRequests() {
        // Check if any drivers are available
        boolean driversAvailable = checkDriverAvailability();

        if (!driversAvailable) {
            log.debug("No available drivers - skipping queue processing");
            return;
        }

        // Get queued requests ordered by priority and time
        List<QueuedRequest> queuedRequests = queuedRequestRepository.findQueuedRequestsOrderedByPriority();

        if (queuedRequests.isEmpty()) {
            return;
        }

        log.info("Processing {} queued requests with available drivers", queuedRequests.size());

        for (QueuedRequest queuedRequest : queuedRequests) {
            try {
                // Check if we still have available drivers
                if (!checkDriverAvailability()) {
                    log.info("No more available drivers - stopping queue processing");
                    break;
                }

                // Check if request has expired
                if (queuedRequest.getExpiresAt().isBefore(ZonedDateTime.now())) {
                    queuedRequest.setStatus("EXPIRED");
                    queuedRequestRepository.save(queuedRequest);
                    continue;
                }

                // Mark as processing
                queuedRequest.setStatus("PROCESSING");
                queuedRequestRepository.save(queuedRequest);

                // Find the ride request
                RideRequest rideRequest = rideRequestRepository.findById(queuedRequest.getRideRequestId())
                        .orElseThrow(() -> new RuntimeException("Ride request not found: " + queuedRequest.getRideRequestId()));

                // Create payment request event
                PaymentRequestEvent paymentRequestEvent = PaymentRequestEvent.builder()
                        .sagaId(queuedRequest.getSagaId())
                        .rideRequestId(rideRequest.getId())
                        .customerEmail(rideRequest.getCustomerEmail())
                        .amount(rideRequest.getEstimatedPrice())
                        .description("Taxi ride payment")
                        .build();

                // Save to outbox for reliable messaging
                saveToOutbox(paymentRequestEvent, queuedRequest.getSagaId(), "payment-requests");

                // Mark as completed
                queuedRequest.setStatus("COMPLETED");
                queuedRequestRepository.save(queuedRequest);

                log.info("Successfully processed queued request for ride: {}", queuedRequest.getRideRequestId());

            } catch (Exception e) {
                log.error("Error processing queued request: {}", queuedRequest.getId(), e);
                // Don't mark as failed - retry next time
            }
        }
    }

    private boolean checkDriverAvailability() {
        try {
            // Call driver service to check availability
            String driverServiceUrl = "http://localhost:4768/api/driver/available-count";
            Integer availableCount = restTemplate.getForObject(driverServiceUrl, Integer.class);
            return availableCount != null && availableCount > 0;
        } catch (Exception e) {
            log.warn("Failed to check driver availability, assuming drivers are available", e);
            return true; // Assume drivers are available if service is down
        }
    }

    private void queueRideRequest(RideRequest rideRequest, CallTaxiRequest request) {
        try {
            UUID sagaId = UUID.randomUUID();
            ZonedDateTime now = ZonedDateTime.now();

            // Create driver request event for later processing
            DriverRequestEvent driverRequestEvent = DriverRequestEvent.builder()
                    .sagaId(sagaId)
                    .rideRequestId(rideRequest.getId())
                    .customerEmail(request.getCustomerEmail())
                    .pickupLocation(rideRequest.getPickupLocation())
                    .destinationLocation(rideRequest.getDestinationLocation())
                    .estimatedPrice(rideRequest.getEstimatedPrice())
                    .build();

            String payload = objectMapper.writeValueAsString(driverRequestEvent);

            QueuedRequest queuedRequest = QueuedRequest.builder()
                    .rideRequestId(rideRequest.getId())
                    .sagaId(sagaId)
                    .customerEmail(request.getCustomerEmail())
                    .driverRequestPayload(payload)
                    .queuedAt(now)
                    .expiresAt(now.plusMinutes(10)) // 10 minutes timeout
                    .priority(1) // Default priority
                    .status("QUEUED")
                    .build();

            queuedRequestRepository.save(queuedRequest);
            log.info("Queued ride request for customer: {} with 10-minute timeout", request.getCustomerEmail());

        } catch (Exception e) {
            log.error("Failed to queue ride request", e);
        }
    }

    private Customer findOrCreateCustomer(String email) {
        return customerRepository.findByEmail(email)
                .orElseGet(() -> {
                    Customer newCustomer = Customer.builder()
                            .email(email)
                            .name("Customer") // In real app, get from JWT or registration
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

        // Calculate estimated price
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
