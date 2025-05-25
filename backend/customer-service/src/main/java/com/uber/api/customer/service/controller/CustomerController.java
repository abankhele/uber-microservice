package com.uber.api.customer.service.controller;

import com.uber.api.customer.service.dto.CallTaxiRequest;
import com.uber.api.customer.service.dto.RideStatusResponse;
import com.uber.api.customer.service.service.CustomerDomainService;
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

    @PostMapping("/call")
    public ResponseEntity<RideStatusResponse> callTaxi(@Valid @RequestBody CallTaxiRequest request) {
        log.info("Received taxi call request from customer: {}", request.getCustomerEmail());

        try {
            RideStatusResponse response = customerDomainService.callTaxi(request);
            log.info("Taxi call request processed successfully for customer: {}", request.getCustomerEmail());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing taxi call request for customer: {}", request.getCustomerEmail(), e);
            return ResponseEntity.badRequest().build();
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
            return ResponseEntity.badRequest().build();
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
}
