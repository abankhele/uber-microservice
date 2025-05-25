package com.uber.api.driver.service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.driver.service.service.DriverDomainService;
import com.uber.api.shared.events.DriverRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DriverRequestListener {

    private final DriverDomainService driverDomainService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "driver-requests", groupId = "driver-service-group")
    public void handleDriverRequest(String message) {
        log.info("Received driver request: {}", message);

        try {
            DriverRequestEvent driverRequest = objectMapper.readValue(message, DriverRequestEvent.class);

            // Process driver assignment through domain service
            driverDomainService.assignDriver(driverRequest);

            log.info("Driver request processed successfully for ride: {}", driverRequest.getRideRequestId());

        } catch (Exception e) {
            log.error("Error processing driver request: {}", message, e);
        }
    }
}
