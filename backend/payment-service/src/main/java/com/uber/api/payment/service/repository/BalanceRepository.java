package com.uber.api.payment.service.repository;

import com.uber.api.payment.service.entity.Balance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BalanceRepository extends JpaRepository<Balance, String> {
    Optional<Balance> findByCustomerEmail(String customerEmail);
}
