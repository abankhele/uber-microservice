package com.uber.api.customer.service.service;

import com.uber.api.customer.service.dto.CallTaxiRequest;
import com.uber.api.customer.service.dto.RideStatusResponse;
import com.uber.api.customer.service.entity.Customer;
import com.uber.api.customer.service.repository.CustomerRepository;
import com.uber.api.customer.service.repository.RideRequestRepository;
import com.uber.api.shared.constants.CustomerStatus;
import com.uber.api.shared.constants.RideStatus;
import com.uber.api.shared.entities.RideRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class RideMatchingService {

    private final CustomerDomainService customerDomainService;
    private final CustomerRepository customerRepository;
    private final RideRequestRepository rideRequestRepository;


    private static final AtomicInteger requestCounter = new AtomicInteger(0);
    private static final int MAX_DRIVERS = 3;

    @Transactional
    public RideStatusResponse requestRide(CallTaxiRequest request) {
        log.info("=== RIDE REQUEST FOR: {} ===", request.getCustomerEmail());

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


            int currentRequests = requestCounter.get();
            log.info("Current processing requests: {}, Max drivers: {} for customer: {}",
                    currentRequests, MAX_DRIVERS, request.getCustomerEmail());

            if (currentRequests < MAX_DRIVERS) {

                int slot = requestCounter.incrementAndGet();
                log.info("✅ IMMEDIATE PROCESSING: Slot {} for {}", slot, request.getCustomerEmail());

                customerDomainService.startSagaForRide(savedRideRequest);

                return RideStatusResponse.builder()
                        .rideRequestId(savedRideRequest.getId())
                        .status(RideStatus.CREATED)
                        .customerEmail(request.getCustomerEmail())
                        .estimatedPrice(savedRideRequest.getEstimatedPrice())
                        .createdAt(savedRideRequest.getCreatedAt())
                        .statusMessage("Processing your ride request...")
                        .build();

            } else {
                // **QUEUE FOR LATER PROCESSING**
                log.warn("🚫 ALL SLOTS TAKEN - ADDING TO QUEUE: {}", request.getCustomerEmail());

                savedRideRequest.setStatus(RideStatus.DRIVER_SEARCHING);
                rideRequestRepository.save(savedRideRequest);

                customerDomainService.addToExistingQueue(savedRideRequest);

                return RideStatusResponse.builder()
                        .rideRequestId(savedRideRequest.getId())
                        .status(RideStatus.DRIVER_SEARCHING)
                        .customerEmail(request.getCustomerEmail())
                        .estimatedPrice(savedRideRequest.getEstimatedPrice())
                        .createdAt(savedRideRequest.getCreatedAt())
                        .statusMessage("All drivers are busy. You're in queue for the next available driver.")
                        .build();
            }

        } catch (Exception e) {
            log.error("❌ ERROR processing ride request for {}: {}", request.getCustomerEmail(), e.getMessage());
            throw new RuntimeException("Failed to process ride request: " + e.getMessage());
        }
    }

    public void onDriverAvailable() {
        log.info("🔄 Driver became available - triggering queue processing");
        customerDomainService.processQueuedRequests();
    }

    public static void releaseSlot(String customerEmail) {
        int remaining = requestCounter.decrementAndGet();
        log.info("🔓 Released slot for {} - remaining: {}", customerEmail, remaining);
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
