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

@Slf4j
@Service
@RequiredArgsConstructor
public class RideMatchingService {

    private final CustomerDomainService customerDomainService;
    private final CustomerRepository customerRepository;
    private final RideRequestRepository rideRequestRepository;

    @Transactional
    public RideStatusResponse requestRide(CallTaxiRequest request) {
        log.info("=== SIMPLE RIDE REQUEST FOR: {} ===", request.getCustomerEmail());

        try {
            // Find or create customer
            Customer customer = findOrCreateCustomer(request.getCustomerEmail());
            validateCustomerCanCallTaxi(customer);

            // **CHECK DRIVER AVAILABILITY FIRST**
            int availableDrivers = customerDomainService.getAvailableDriverCount();
            log.info("Available drivers: {} for customer: {}", availableDrivers, request.getCustomerEmail());

            if (availableDrivers == 0) {
                // **NO DRIVERS AVAILABLE - IMMEDIATE RESPONSE**
                log.warn("🚫 NO DRIVERS AVAILABLE - IMMEDIATE REJECTION for {}", request.getCustomerEmail());

                return RideStatusResponse.builder()
                        .customerEmail(request.getCustomerEmail())
                        .status(RideStatus.DRIVER_UNAVAILABLE)
                        .statusMessage("No drivers available nearby. Please try again later.")
                        .build();
            }

            // Create ride request
            RideRequest rideRequest = customerDomainService.createRideRequestFromRequest(request);
            RideRequest savedRideRequest = rideRequestRepository.save(rideRequest);

            // Update customer status
            customer.setStatus(CustomerStatus.REQUESTING);
            customer.setCurrentRideRequestId(savedRideRequest.getId());
            customerRepository.save(customer);

            // **START SAGA IMMEDIATELY**
            log.info("✅ STARTING SAGA IMMEDIATELY for {}", request.getCustomerEmail());
            customerDomainService.startSagaForRide(savedRideRequest);

            return RideStatusResponse.builder()
                    .rideRequestId(savedRideRequest.getId())
                    .status(RideStatus.CREATED)
                    .customerEmail(request.getCustomerEmail())
                    .estimatedPrice(savedRideRequest.getEstimatedPrice())
                    .createdAt(savedRideRequest.getCreatedAt())
                    .statusMessage("Processing your ride request...")
                    .build();

        } catch (Exception e) {
            log.error("❌ ERROR processing ride request for {}: {}", request.getCustomerEmail(), e.getMessage());
            throw new RuntimeException("Failed to process ride request: " + e.getMessage());
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
