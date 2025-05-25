package com.uber.api.payment.service.repository;

import com.uber.api.payment.service.entity.PaymentOutbox;
import com.uber.api.shared.outbox.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentOutboxRepository extends JpaRepository<PaymentOutbox, UUID> {
    List<PaymentOutbox> findByStatusOrderByCreatedAt(OutboxStatus status);
    List<PaymentOutbox> findBySagaId(UUID sagaId);
}
