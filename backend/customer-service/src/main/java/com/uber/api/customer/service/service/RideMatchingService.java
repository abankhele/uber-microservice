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
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class RideMatchingService {

    private final CustomerDomainService customerDomainService;
    private final CustomerRepository customerRepository;
    private final RideRequestRepository rideRequestRepository;
    private final RestTemplate restTemplate;

    @Transactional
    public synchronized RideStatusResponse requestRide(CallTaxiRequest request) {
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

            // **CRITICAL FIX: Use actual driver availability from database**
            int availableDrivers = customerDomainService.getAvailableDriverCount();

            log.info("Available drivers: {} for customer: {}", availableDrivers, request.getCustomerEmail());

            if (availableDrivers > 0) {
                // **IMMEDIATE PROCESSING**
                log.info("✅ IMMEDIATE PROCESSING: {} available drivers for {}", availableDrivers, request.getCustomerEmail());

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
                log.warn("🚫 NO AVAILABLE DRIVERS - ADDING TO QUEUE: {}", request.getCustomerEmail());
                return addToQueue(savedRideRequest, request);
            }

        } catch (Exception e) {
            log.error("❌ ERROR processing ride request for {}: {}", request.getCustomerEmail(), e.getMessage());
            throw new RuntimeException("Failed to process ride request: " + e.getMessage());
        }
    }

    // **HELPER METHOD: Extract queue logic**
    private RideStatusResponse addToQueue(RideRequest savedRideRequest, CallTaxiRequest request) {
        savedRideRequest.setStatus(RideStatus.DRIVER_SEARCHING);
        rideRequestRepository.save(savedRideRequest);

        // **CRITICAL FIX: Direct queue addition with error handling**
        try {
            log.info("🔄 ATTEMPTING TO ADD TO QUEUE: {}", request.getCustomerEmail());
            customerDomainService.addToExistingQueue(savedRideRequest);
            log.info("✅ SUCCESSFULLY ADDED TO QUEUE: {}", request.getCustomerEmail());
        } catch (Exception e) {
            log.error("❌ FAILED TO ADD TO QUEUE for {}: {}", request.getCustomerEmail(), e.getMessage(), e);
            throw new RuntimeException("Failed to add to queue: " + e.getMessage());
        }

        return RideStatusResponse.builder()
                .rideRequestId(savedRideRequest.getId())
                .status(RideStatus.DRIVER_SEARCHING)
                .customerEmail(request.getCustomerEmail())
                .estimatedPrice(savedRideRequest.getEstimatedPrice())
                .createdAt(savedRideRequest.getCreatedAt())
                .statusMessage("All drivers are busy. You're in queue for the next available driver.")
                .build();
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


