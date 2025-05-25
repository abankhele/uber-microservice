package com.uber.api.customer.service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.customer.service.repository.RideRequestRepository;
import com.uber.api.customer.service.repository.CustomerRepository;
import com.uber.api.shared.constants.CustomerStatus;
import com.uber.api.shared.constants.RideStatus;
import com.uber.api.shared.entities.RideRequest;
import com.uber.api.shared.events.DriverResponseEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DriverResponseListener {

    private final RideRequestRepository rideRequestRepository;
    private final CustomerRepository customerRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "driver-responses", groupId = "customer-service-group")
    @Transactional
    public void handleDriverResponse(String message) {
        log.info("Received driver response: {}", message);

        try {
            DriverResponseEvent driverResponse = objectMapper.readValue(message, DriverResponseEvent.class);

            // Find the ride request
            RideRequest rideRequest = rideRequestRepository.findById(driverResponse.getRideRequestId())
                    .orElseThrow(() -> new RuntimeException("Ride request not found: " + driverResponse.getRideRequestId()));

            if (driverResponse.isAccepted()) {
                // Driver accepted the ride
                log.info("Driver {} accepted ride request: {}", driverResponse.getDriverEmail(), driverResponse.getRideRequestId());

                rideRequest.setStatus(RideStatus.DRIVER_ASSIGNED);
                rideRequest.setDriverEmail(driverResponse.getDriverEmail());
                rideRequestRepository.save(rideRequest);

                // Update customer status
                customerRepository.findByEmail(rideRequest.getCustomerEmail()).ifPresent(customer -> {
                    customer.setStatus(CustomerStatus.ON_RIDE);
                    customerRepository.save(customer);
                });

            } else {
                // Driver rejected or no driver available
                log.warn("Driver rejected or unavailable for ride request: {}", driverResponse.getRideRequestId());

                rideRequest.setStatus(RideStatus.DRIVER_UNAVAILABLE);
                rideRequestRepository.save(rideRequest);

                // Reset customer status
                customerRepository.findByEmail(rideRequest.getCustomerEmail()).ifPresent(customer -> {
                    customer.setStatus(CustomerStatus.AVAILABLE);
                    customer.setCurrentRideRequestId(null);
                    customerRepository.save(customer);
                });
            }

            log.info("Driver response processed successfully for ride: {}", driverResponse.getRideRequestId());

        } catch (Exception e) {
            log.error("Error processing driver response: {}", message, e);
        }
    }
}
