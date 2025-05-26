package com.uber.api.customer.service.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RideMatchingService {

    private final CustomerDomainService customerDomainService;
    private final CustomerRepository customerRepository;
    private final RideRequestRepository rideRequestRepository;
    private final QueuedRequestRepository queuedRequestRepository;
    private final RestTemplate restTemplate;

    // **CENTRALIZED IN-MEMORY QUEUE for immediate processing**
    private final ConcurrentLinkedQueue<RideRequest> immediateQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void startMatchingService() {
        // Start immediate queue processor - runs every 2 seconds
        scheduler.scheduleWithFixedDelay(this::processImmediateQueue, 0, 2, TimeUnit.SECONDS);
        log.info("✅ RideMatchingService started - Immediate queue processor running");
    }

    @Transactional
    public RideStatusResponse requestRide(CallTaxiRequest request) {
        log.info("=== RIDE MATCHING REQUEST FOR: {} ===", request.getCustomerEmail());

        try {
            // Find or create customer
            Customer customer = findOrCreateCustomer(request.getCustomerEmail());
            validateCustomerCanCallTaxi(customer);

            // Create ride request using existing logic
            RideRequest rideRequest = customerDomainService.createRideRequestFromRequest(request);
            RideRequest savedRideRequest = rideRequestRepository.save(rideRequest);

            // Update customer status
            customer.setStatus(CustomerStatus.REQUESTING);
            customer.setCurrentRideRequestId(savedRideRequest.getId());
            customerRepository.save(customer);

            // **INTELLIGENT ROUTING: Check driver availability**
            int availableDrivers = getAvailableDriverCount();
            log.info("Available drivers: {} for customer: {}", availableDrivers, request.getCustomerEmail());

            if (availableDrivers > 0) {
                // **IMMEDIATE PROCESSING via existing SAGA**
                log.info("✅ IMMEDIATE PROCESSING: {} drivers available", availableDrivers);

                // Add to immediate queue for fast processing
                immediateQueue.offer(savedRideRequest);

                return RideStatusResponse.builder()
                        .rideRequestId(savedRideRequest.getId())
                        .status(RideStatus.CREATED)
                        .customerEmail(request.getCustomerEmail())
                        .estimatedPrice(savedRideRequest.getEstimatedPrice())
                        .createdAt(savedRideRequest.getCreatedAt())
                        .statusMessage("Processing your ride request...")
                        .build();

            } else {
                // **QUEUE FOR LATER PROCESSING via existing queue system**
                log.warn("🚫 NO DRIVERS AVAILABLE - USING EXISTING QUEUE: {}", request.getCustomerEmail());

                savedRideRequest.setStatus(RideStatus.DRIVER_SEARCHING);
                rideRequestRepository.save(savedRideRequest);

                // Use existing queue system
                customerDomainService.addToExistingQueue(savedRideRequest);

                return RideStatusResponse.builder()
                        .rideRequestId(savedRideRequest.getId())
                        .status(RideStatus.DRIVER_SEARCHING)
                        .customerEmail(request.getCustomerEmail())
                        .estimatedPrice(savedRideRequest.getEstimatedPrice())
                        .createdAt(savedRideRequest.getCreatedAt())
                        .statusMessage("All drivers are busy. You're in queue for the next available driver. Request will expire in 10 minutes.")
                        .build();
            }

        } catch (Exception e) {
            log.error("❌ ERROR processing ride request for {}: {}", request.getCustomerEmail(), e.getMessage());
            throw new RuntimeException("Failed to process ride request: " + e.getMessage());
        }
    }

    /**
     * **IMMEDIATE QUEUE PROCESSOR**
     * Processes requests when drivers are available immediately
     */
    public void processImmediateQueue() {
        if (immediateQueue.isEmpty()) {
            return;
        }

        int availableDrivers = getAvailableDriverCount();
        if (availableDrivers == 0) {
            // Move requests to persistent queue if no drivers available
            moveToExistingQueue();
            return;
        }

        log.info("=== PROCESSING IMMEDIATE QUEUE: {} requests, {} drivers available ===",
                immediateQueue.size(), availableDrivers);

        int processed = 0;
        while (!immediateQueue.isEmpty() && processed < availableDrivers) {
            RideRequest request = immediateQueue.poll();

            if (request != null) {
                try {
                    // Process through existing SAGA
                    customerDomainService.startSagaForRide(request);
                    processed++;
                    log.info("✅ Processed immediate request for: {}", request.getCustomerEmail());
                } catch (Exception e) {
                    log.error("Error processing immediate request", e);
                    // Add back to queue for retry
                    immediateQueue.offer(request);
                    break;
                }
            }
        }

        if (processed > 0) {
            log.info("✅ Processed {} immediate requests", processed);
        }
    }

    /**
     * **TRIGGER IMMEDIATE PROCESSING when driver becomes available**
     */
    public void onDriverAvailable() {
        log.info("🔄 Driver became available - triggering immediate queue processing");
        processImmediateQueue();

        // Also trigger existing queue processing
        customerDomainService.processQueuedRequests();
    }

    private void moveToExistingQueue() {
        while (!immediateQueue.isEmpty()) {
            RideRequest request = immediateQueue.poll();
            if (request != null) {
                try {
                    request.setStatus(RideStatus.DRIVER_SEARCHING);
                    rideRequestRepository.save(request);
                    customerDomainService.addToExistingQueue(request);
                    log.info("Moved request to persistent queue: {}", request.getCustomerEmail());
                } catch (Exception e) {
                    log.error("Error moving request to queue", e);
                }
            }
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
