package com.uber.api.driver.service.repository;

import com.uber.api.driver.service.entity.DriverOutbox;
import com.uber.api.shared.outbox.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DriverOutboxRepository extends JpaRepository<DriverOutbox, UUID> {
    List<DriverOutbox> findByStatusOrderByCreatedAt(OutboxStatus status);
    List<DriverOutbox> findBySagaId(UUID sagaId);
}
