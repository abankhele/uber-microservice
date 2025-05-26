package com.uber.api.customer.service.controller;

import com.uber.api.customer.service.dto.CallTaxiRequest;
import com.uber.api.customer.service.dto.RideStatusResponse;
import com.uber.api.customer.service.service.CustomerDomainService;
import com.uber.api.customer.service.service.RideMatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // For development only
public class CustomerController {

    private final CustomerDomainService customerDomainService;
    private final RideMatchingService rideMatchingService;

    @PostMapping("/call")
    public ResponseEntity<RideStatusResponse> callTaxi(@Valid @RequestBody CallTaxiRequest request) {
        log.info("Received taxi call request from customer: {}", request.getCustomerEmail());

        try {
            // **USE RideMatchingService for intelligent routing**
            RideStatusResponse response = rideMatchingService.requestRide(request);
            log.info("Taxi call request processed successfully for customer: {}", request.getCustomerEmail());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing taxi call request for customer: {}", request.getCustomerEmail(), e);
            return ResponseEntity.badRequest()
                    .body(RideStatusResponse.builder()
                            .customerEmail(request.getCustomerEmail())
                            .statusMessage("Failed to process taxi request: " + e.getMessage())
                            .build());
        }
    }


    @GetMapping("/status/{customerEmail}")
    public ResponseEntity<RideStatusResponse> getRideStatus(@PathVariable String customerEmail) {
        log.info("Getting ride status for customer: {}", customerEmail);

        try {
            RideStatusResponse response = customerDomainService.getRideStatus(customerEmail);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting ride status for customer: {}", customerEmail, e);
            return ResponseEntity.badRequest()
                    .body(RideStatusResponse.builder()
                            .customerEmail(customerEmail)
                            .statusMessage("Failed to get ride status: " + e.getMessage())
                            .build());
        }
    }

    @PostMapping("/start/{customerEmail}")
    public ResponseEntity<String> startRide(@PathVariable String customerEmail) {
        log.info("Starting ride for customer: {}", customerEmail);

        try {
            customerDomainService.startRide(customerEmail);
            return ResponseEntity.ok("Ride started successfully");

        } catch (Exception e) {
            log.error("Error starting ride for customer: {}", customerEmail, e);
            return ResponseEntity.badRequest().body("Failed to start ride: " + e.getMessage());
        }
    }

    @PostMapping("/complete/{customerEmail}")
    public ResponseEntity<String> completeRide(@PathVariable String customerEmail) {
        log.info("Completing ride for customer: {}", customerEmail);

        try {
            customerDomainService.completeRide(customerEmail);
            return ResponseEntity.ok("Ride completed successfully");

        } catch (Exception e) {
            log.error("Error completing ride for customer: {}", customerEmail, e);
            return ResponseEntity.badRequest().body("Failed to complete ride: " + e.getMessage());
        }
    }

    @PostMapping("/cancel/{customerEmail}")
    public ResponseEntity<String> cancelRide(@PathVariable String customerEmail) {
        log.info("Cancelling ride for customer: {}", customerEmail);

        try {
            customerDomainService.cancelRide(customerEmail);
            return ResponseEntity.ok("Ride cancelled successfully");

        } catch (Exception e) {
            log.error("Error cancelling ride for customer: {}", customerEmail, e);
            return ResponseEntity.badRequest().body("Failed to cancel ride: " + e.getMessage());
        }
    }

    @GetMapping("/debug/queue")
    public ResponseEntity<String> debugQueue() {
        try {
            customerDomainService.debugQueueOrder();
            return ResponseEntity.ok("Queue order logged - check console");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }


    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Customer Service is healthy");
    }
}
