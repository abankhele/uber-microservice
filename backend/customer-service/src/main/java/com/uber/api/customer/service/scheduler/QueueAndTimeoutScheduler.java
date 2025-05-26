package com.uber.api.customer.service.scheduler;

import com.uber.api.customer.service.service.CustomerDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueAndTimeoutScheduler {

    private final CustomerDomainService customerDomainService;

    // **FIXED: Reasonable queue processing interval**
    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    public void processQueuedRequests() {
        try {
            log.debug("🔄 Running queue processor...");
            customerDomainService.processQueuedRequests();
        } catch (Exception e) {
            log.error("Error processing queued requests", e);
        }
    }

    // Clean up expired requests every 30 seconds
    @Scheduled(fixedDelay = 30000)
    public void processExpiredRequests() {
        try {
            customerDomainService.processExpiredRequests();
        } catch (Exception e) {
            log.error("Error processing expired requests", e);
        }
    }
}
