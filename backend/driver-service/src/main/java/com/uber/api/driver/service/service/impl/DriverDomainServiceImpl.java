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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
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
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 5, backoff = @Backoff(delay = 50))
    public DriverResponseEvent assignDriver(DriverRequestEvent driverRequest) {
        log.info("Processing driver assignment for ride: {} (Saga: {})",
                driverRequest.getRideRequestId(), driverRequest.getSagaId());

        try {
            // **FIX 1: Atomic driver count check and assignment**
            long availableCount = driverRepository.countByStatus(DriverStatus.AVAILABLE);
            log.info("Available drivers count: {}", availableCount);

            if (availableCount == 0) {
                log.warn("No available drivers for ride: {}", driverRequest.getRideRequestId());
                return createNoDriverResponse(driverRequest);
            }

            // **FIX 2: Atomic driver assignment with optimistic locking**
            Driver assignedDriver = findAndAtomicallyAssignDriver(driverRequest);

            if (assignedDriver != null) {
                DriverResponseEvent response = DriverResponseEvent.builder()
                        .sagaId(driverRequest.getSagaId())
                        .rideRequestId(driverRequest.getRideRequestId())
                        .driverEmail(assignedDriver.getEmail())
                        .status(DriverStatus.BUSY)
                        .accepted(true)
                        .build();

                saveToOutbox(response, driverRequest.getSagaId(), "driver-responses");
                log.info("Driver {} assigned to ride: {}", assignedDriver.getEmail(), driverRequest.getRideRequestId());

                return response;

            } else {
                log.warn("Failed to assign any driver for ride: {} (race condition)",
                        driverRequest.getRideRequestId());
                return createNoDriverResponse(driverRequest);
            }

        } catch (Exception e) {
            log.error("Error assigning driver for ride: {}", driverRequest.getRideRequestId(), e);
            return createErrorResponse(driverRequest, e.getMessage());
        }
    }

    /**
     * **FIX 3: Atomic driver assignment using optimistic locking**
     */
    private Driver findAndAtomicallyAssignDriver(DriverRequestEvent driverRequest) {
        Double pickupLat = driverRequest.getPickupLocation().getLatitude();
        Double pickupLng = driverRequest.getPickupLocation().getLongitude();
        String city = driverRequest.getPickupLocation().getCity();

        // Get available drivers sorted by distance
        List<Driver> availableDrivers = driverRepository.findAvailableDriversInCity(city);
        if (availableDrivers.isEmpty()) {
            availableDrivers = driverRepository.findAllAvailableDrivers();
        }

        // Sort by distance to pickup location
        availableDrivers.sort(Comparator.comparing(driver ->
                driver.distanceToLocation(pickupLat, pickupLng)));

        // **FIX 4: Try to assign drivers in order with retry logic**
        for (Driver driver : availableDrivers) {
            try {
                // Refresh driver from database to get latest version
                Driver freshDriver = driverRepository.findById(driver.getId())
                        .orElse(null);

                if (freshDriver == null || freshDriver.getStatus() != DriverStatus.AVAILABLE) {
                    log.debug("Driver {} no longer available, trying next", driver.getEmail());
                    continue;
                }

                // **ATOMIC OPERATION: Update with optimistic locking**
                freshDriver.setStatus(DriverStatus.BUSY);
                freshDriver.setCurrentRideRequestId(driverRequest.getRideRequestId());

                // This will throw OptimisticLockingFailureException if driver was already assigned
                Driver savedDriver = driverRepository.save(freshDriver);

                log.info("Successfully assigned driver {} to ride {}",
                        driver.getEmail(), driverRequest.getRideRequestId());
                return savedDriver;

            } catch (OptimisticLockingFailureException e) {
                log.warn("Driver {} was already assigned to another ride, trying next driver",
                        driver.getEmail());
                // Continue to next driver
            } catch (Exception e) {
                log.error("Error assigning driver {}: {}", driver.getEmail(), e.getMessage());
                // Continue to next driver
            }
        }

        return null; // No driver could be assigned
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

    @Override
    @Transactional
    public void resetAllDriversToAvailable() {
        log.info("Resetting all drivers to AVAILABLE status");

        List<Driver> allDrivers = driverRepository.findAll();
        int resetCount = 0;

        for (Driver driver : allDrivers) {
            if (driver.getStatus() != DriverStatus.AVAILABLE) {
                log.info("Resetting driver {} from {} to AVAILABLE",
                        driver.getEmail(), driver.getStatus());

                driver.setStatus(DriverStatus.AVAILABLE);
                driver.setCurrentRideRequestId(null);
                driverRepository.save(driver);
                resetCount++;
            }
        }

        log.info("Reset {} drivers to AVAILABLE status", resetCount);
    }

    @Override
    @Transactional
    public void completeDriverRide(String driverEmail) {
        log.info("Completing ride for driver: {}", driverEmail);

        driverRepository.findByEmail(driverEmail).ifPresentOrElse(
                driver -> {
                    log.info("Resetting driver {} status from {} to AVAILABLE",
                            driverEmail, driver.getStatus());

                    driver.setStatus(DriverStatus.AVAILABLE);
                    driver.setCurrentRideRequestId(null);
                    driverRepository.save(driver);

                    log.info("Driver {} is now AVAILABLE for new rides", driverEmail);
                },
                () -> log.warn("Driver not found: {}", driverEmail)
        );
    }

    @Override
    public int getAvailableDriverCount() {
        return (int) driverRepository.countByStatus(DriverStatus.AVAILABLE);
    }

    @Override
    public boolean hasAvailableDrivers() {
        return driverRepository.countByStatus(DriverStatus.AVAILABLE) > 0;
    }

    private DriverResponseEvent createNoDriverResponse(DriverRequestEvent request) {
        return DriverResponseEvent.builder()
                .sagaId(request.getSagaId())
                .rideRequestId(request.getRideRequestId())
                .driverEmail(null)
                .status(DriverStatus.AVAILABLE)
                .accepted(false)
                .rejectionReason("No drivers available in the area")
                .build();
    }

    private DriverResponseEvent createErrorResponse(DriverRequestEvent request, String errorMessage) {
        return DriverResponseEvent.builder()
                .sagaId(request.getSagaId())
                .rideRequestId(request.getRideRequestId())
                .driverEmail(null)
                .status(DriverStatus.AVAILABLE)
                .accepted(false)
                .rejectionReason("Driver assignment failed: " + errorMessage)
                .build();
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
