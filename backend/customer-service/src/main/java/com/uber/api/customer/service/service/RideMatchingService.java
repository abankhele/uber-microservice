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
    public RideStatusResponse requestRide(CallTaxiRequest request) {
        log.info("=== RIDE MATCHING REQUEST FOR: {} ===", request.getCustomerEmail());

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

            // **CRITICAL FIX: Check driver availability FIRST**
            int availableDrivers = getAvailableDriverCount();
            log.info("Available drivers: {} for customer: {}", availableDrivers, request.getCustomerEmail());

            if (availableDrivers > 0) {
                // **IMMEDIATE PROCESSING - Start SAGA directly**
                log.info("✅ IMMEDIATE PROCESSING: {} drivers available", availableDrivers);
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
                log.warn("🚫 NO DRIVERS AVAILABLE - ADDING TO QUEUE: {}", request.getCustomerEmail());

                savedRideRequest.setStatus(RideStatus.DRIVER_SEARCHING);
                rideRequestRepository.save(savedRideRequest);

                // Use existing persistent queue system
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
     * **TRIGGER QUEUE PROCESSING when driver becomes available**
     */
    public void onDriverAvailable() {
        log.info("🔄 Driver became available - triggering persistent queue processing");
        customerDomainService.processQueuedRequests();
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
