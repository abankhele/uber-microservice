package com.uber.api.customer.service.messaging;

import com.uber.api.customer.service.service.CustomerDomainService;
import com.uber.api.customer.service.service.RideMatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;



    @Slf4j
    @Component
    @RequiredArgsConstructor
    public class DriverAvailableListener {

        private final CustomerDomainService customerDomainService;
        private final RideMatchingService rideMatchingService; // **ADD THIS**

        @KafkaListener(topics = "driver-available", groupId = "customer-service-group")
        public void handleDriverAvailable(String driverEmail) {
            log.info("🔄 DRIVER AVAILABLE EVENT RECEIVED: {}", driverEmail);

            try {
                // **TRIGGER BOTH immediate and persistent queue processing**
                rideMatchingService.onDriverAvailable();
                log.info("✅ Queue processing triggered by driver available event");
            } catch (Exception e) {
                log.error("❌ Error processing queue after driver available event", e);
            }
        }
    }


