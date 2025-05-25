package com.uber.api.payment.service.repository;

import com.uber.api.payment.service.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    List<Transaction> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);
    Optional<Transaction> findBySagaId(UUID sagaId);
    List<Transaction> findByRideRequestId(UUID rideRequestId);
}
