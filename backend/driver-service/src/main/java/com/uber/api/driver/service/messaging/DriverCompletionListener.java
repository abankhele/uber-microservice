package com.uber.api.driver.service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.driver.service.repository.DriverRepository;
import com.uber.api.shared.constants.DriverStatus;
import com.uber.api.shared.events.DriverCompletionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DriverCompletionListener {

    private final DriverRepository driverRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "driver-completion", groupId = "driver-service-group")
    @Transactional
    public void handleRideCompletion(String message) {
        log.info("Received driver completion event: {}", message);

        try {
            DriverCompletionEvent event = objectMapper.readValue(message, DriverCompletionEvent.class);

            driverRepository.findByEmail(event.getDriverEmail()).ifPresentOrElse(
                    driver -> {
                        log.info("Resetting driver {} status from {} to AVAILABLE",
                                event.getDriverEmail(), driver.getStatus());

                        driver.setStatus(DriverStatus.AVAILABLE);
                        driver.setCurrentRideRequestId(null);
                        driverRepository.save(driver);

                        log.info("Driver {} is now AVAILABLE for new rides", event.getDriverEmail());
                    },
                    () -> log.warn("Driver not found: {}", event.getDriverEmail())
            );

        } catch (Exception e) {
            log.error("Error processing driver completion event: {}", message, e);
        }
    }
}
