package com.uber.api.driver.service.controller;

import com.uber.api.driver.service.service.DriverDomainService;
import com.uber.api.shared.constants.DriverStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/driver")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DriverController {

    private final DriverDomainService driverDomainService;

    @PostMapping("/status/{driverEmail}")
    public ResponseEntity<String> updateStatus(@PathVariable String driverEmail,
                                               @RequestParam DriverStatus status) {
        log.info("Updating status for driver: {} to: {}", driverEmail, status);

        try {
            driverDomainService.updateDriverStatus(driverEmail, status);
            return ResponseEntity.ok("Driver status updated successfully");
        } catch (Exception e) {
            log.error("Error updating driver status", e);
            return ResponseEntity.badRequest().body("Failed to update driver status: " + e.getMessage());
        }
    }

    @PostMapping("/location/{driverEmail}")
    public ResponseEntity<String> updateLocation(@PathVariable String driverEmail,
                                                 @RequestParam Double latitude,
                                                 @RequestParam Double longitude,
                                                 @RequestParam String city) {
        log.info("Updating location for driver: {}", driverEmail);

        try {
            driverDomainService.updateDriverLocation(driverEmail, latitude, longitude, city);
            return ResponseEntity.ok("Driver location updated successfully");
        } catch (Exception e) {
            log.error("Error updating driver location", e);
            return ResponseEntity.badRequest().body("Failed to update driver location: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Driver Service is healthy");
    }
}
