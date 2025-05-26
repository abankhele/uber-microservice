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


    @Scheduled(fixedDelay = 3000) // Every 3 seconds
    public void processQueuedRequests() {
        try {
            log.debug("🔄 Running queue processor...");
            customerDomainService.processQueuedRequests();
        } catch (Exception e) {
            log.error("Error processing queued requests", e);
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void processExpiredRequests() {
        try {
            customerDomainService.processExpiredRequests();
        } catch (Exception e) {
            log.error("Error processing expired requests", e);
        }
    }
}
