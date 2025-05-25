package com.uber.api.payment.service.controller;

import com.uber.api.payment.service.service.PaymentDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PaymentController {

    private final PaymentDomainService paymentDomainService;

    @GetMapping("/balance/{customerEmail}")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable String customerEmail) {
        log.info("Getting balance for customer: {}", customerEmail);

        try {
            BigDecimal balance = paymentDomainService.getBalance(customerEmail);
            return ResponseEntity.ok(balance);
        } catch (Exception e) {
            log.error("Error getting balance for customer: {}", customerEmail, e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/balance/{customerEmail}/add")
    public ResponseEntity<String> addBalance(@PathVariable String customerEmail,
                                             @RequestParam BigDecimal amount) {
        log.info("Adding balance {} for customer: {}", amount, customerEmail);

        try {
            paymentDomainService.addBalance(customerEmail, amount);
            return ResponseEntity.ok("Balance added successfully");
        } catch (Exception e) {
            log.error("Error adding balance for customer: {}", customerEmail, e);
            return ResponseEntity.badRequest().body("Failed to add balance: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Payment Service is healthy");
    }
}
