package com.uber.api.customer.service.repository;

import com.uber.api.customer.service.entity.CustomerOutbox;
import com.uber.api.shared.outbox.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CustomerOutboxRepository extends JpaRepository<CustomerOutbox, UUID> {
    List<CustomerOutbox> findByStatusOrderByCreatedAt(OutboxStatus status);
    List<CustomerOutbox> findBySagaId(UUID sagaId);
}
