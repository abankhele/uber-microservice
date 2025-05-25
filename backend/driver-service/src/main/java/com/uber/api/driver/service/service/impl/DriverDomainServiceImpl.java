package com.uber.api.driver.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.driver.service.entity.Driver;
import com.uber.api.driver.service.entity.DriverOutbox;
import com.uber.api.driver.service.repository.DriverOutboxRepository;
import com.uber.api.driver.service.repository.DriverRepository;
import com.uber.api.driver.service.service.DriverDomainService;
import com.uber.api.shared.constants.DriverStatus;
import com.uber.api.shared.events.DriverRequestEvent;
import com.uber.api.shared.events.DriverResponseEvent;
import com.uber.api.shared.outbox.OutboxStatus;
import com.uber.api.shared.saga.SagaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverDomainServiceImpl implements DriverDomainService {

    private final DriverRepository driverRepository;
    private final DriverOutboxRepository driverOutboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public DriverResponseEvent assignDriver(DriverRequestEvent driverRequest) {
        log.info("Processing driver assignment for ride: {}", driverRequest.getRideRequestId());

        try {
            // Find nearest available driver
            Driver assignedDriver = findNearestAvailableDriver(driverRequest);

            if (assignedDriver != null) {
                // Assign driver to ride
                assignedDriver.setStatus(DriverStatus.BUSY);
                assignedDriver.setCurrentRideRequestId(driverRequest.getRideRequestId());
                driverRepository.save(assignedDriver);

                // Create successful driver response
                DriverResponseEvent response = DriverResponseEvent.builder()
                        .sagaId(driverRequest.getSagaId())
                        .rideRequestId(driverRequest.getRideRequestId())
                        .driverEmail(assignedDriver.getEmail())
                        .status(DriverStatus.BUSY)
                        .accepted(true)
                        .build();

                // Save to outbox for reliable messaging
                saveToOutbox(response, driverRequest.getSagaId(), "driver-responses");

                log.info("Driver {} assigned to ride: {}", assignedDriver.getEmail(), driverRequest.getRideRequestId());

                return response;

            } else {
                log.warn("No available drivers found for ride: {}", driverRequest.getRideRequestId());

                // Create driver unavailable response
                DriverResponseEvent response = DriverResponseEvent.builder()
                        .sagaId(driverRequest.getSagaId())
                        .rideRequestId(driverRequest.getRideRequestId())
                        .driverEmail(null)
                        .status(DriverStatus.AVAILABLE)
                        .accepted(false)
                        .rejectionReason("No drivers available in the area")
                        .build();

                // Save to outbox
                saveToOutbox(response, driverRequest.getSagaId(), "driver-responses");

                return response;
            }

        } catch (Exception e) {
            log.error("Error assigning driver for ride: {}", driverRequest.getRideRequestId(), e);

            // Create error response
            DriverResponseEvent response = DriverResponseEvent.builder()
                    .sagaId(driverRequest.getSagaId())
                    .rideRequestId(driverRequest.getRideRequestId())
                    .driverEmail(null)
                    .status(DriverStatus.AVAILABLE)
                    .accepted(false)
                    .rejectionReason("Driver assignment failed: " + e.getMessage())
                    .build();

            saveToOutbox(response, driverRequest.getSagaId(), "driver-responses");

            return response;
        }
    }

    @Override
    @Transactional
    public void updateDriverStatus(String driverEmail, DriverStatus status) {
        log.info("Updating driver status for: {} to: {}", driverEmail, status);

        driverRepository.findByEmail(driverEmail).ifPresentOrElse(
                driver -> {
                    driver.setStatus(status);
                    if (status == DriverStatus.AVAILABLE) {
                        driver.setCurrentRideRequestId(null);
                    }
                    driverRepository.save(driver);
                    log.info("Driver status updated successfully for: {}", driverEmail);
                },
                () -> log.warn("Driver not found: {}", driverEmail)
        );
    }

    @Override
    @Transactional
    public void updateDriverLocation(String driverEmail, Double latitude, Double longitude, String city) {
        log.info("Updating location for driver: {} to: {}, {}", driverEmail, latitude, longitude);

        driverRepository.findByEmail(driverEmail).ifPresentOrElse(
                driver -> {
                    driver.setCurrentLatitude(latitude);
                    driver.setCurrentLongitude(longitude);
                    driver.setCurrentCity(city);
                    driverRepository.save(driver);
                    log.info("Driver location updated successfully for: {}", driverEmail);
                },
                () -> log.warn("Driver not found: {}", driverEmail)
        );
    }

    private Driver findNearestAvailableDriver(DriverRequestEvent driverRequest) {
        // Get pickup location
        Double pickupLat = driverRequest.getPickupLocation().getLatitude();
        Double pickupLng = driverRequest.getPickupLocation().getLongitude();
        String city = driverRequest.getPickupLocation().getCity();

        // First try to find drivers in the same city
        List<Driver> cityDrivers = driverRepository.findAvailableDriversInCity(city);

        if (!cityDrivers.isEmpty()) {
            // Find nearest driver in the city
            return cityDrivers.stream()
                    .min(Comparator.comparing(driver ->
                            driver.distanceToLocation(pickupLat, pickupLng)))
                    .orElse(null);
        }

        // If no drivers in city, find nearest available driver overall
        List<Driver> allAvailableDrivers = driverRepository.findAllAvailableDrivers();

        return allAvailableDrivers.stream()
                .min(Comparator.comparing(driver ->
                        driver.distanceToLocation(pickupLat, pickupLng)))
                .orElse(null);
    }

    private void saveToOutbox(Object event, UUID sagaId, String eventType) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            DriverOutbox outboxEvent = DriverOutbox.builder()
                    .sagaId(sagaId)
                    .eventType(eventType)
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .sagaStatus(SagaStatus.PROCESSING)
                    .createdAt(ZonedDateTime.now())
                    .build();

            driverOutboxRepository.save(outboxEvent);

        } catch (Exception e) {
            log.error("Failed to save event to outbox", e);
            throw new RuntimeException("Failed to save event to outbox", e);
        }
    }
}
