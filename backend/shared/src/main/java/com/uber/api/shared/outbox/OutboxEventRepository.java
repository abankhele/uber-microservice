package com.uber.api.shared.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByStatusOrderByCreatedAt(OutboxStatus status);
    List<OutboxEvent> findBySagaId(UUID sagaId);
}
