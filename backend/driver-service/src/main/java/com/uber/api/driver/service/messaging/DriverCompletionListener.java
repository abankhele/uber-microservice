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
        log.info(" RECEIVED DRIVER COMPLETION EVENT: {}", message);

        try {
            DriverCompletionEvent event = objectMapper.readValue(message, DriverCompletionEvent.class);

            driverRepository.findByEmail(event.getDriverEmail()).ifPresentOrElse(
                    driver -> {
                        log.info(" RESETTING DRIVER {} FROM {} TO AVAILABLE",
                                event.getDriverEmail(), driver.getStatus());

                        driver.setStatus(DriverStatus.AVAILABLE);
                        driver.setCurrentRideRequestId(null);
                        driverRepository.save(driver);

                        log.info(" DRIVER {} IS NOW AVAILABLE FOR NEW RIDES", event.getDriverEmail());
                    },
                    () -> log.warn(" DRIVER NOT FOUND: {}", event.getDriverEmail())
            );

        } catch (Exception e) {
            log.error(" ERROR PROCESSING DRIVER COMPLETION: {}", message, e);
        }
    }
}
