package com.uber.api.customer.service.repository;

import com.uber.api.customer.service.entity.Customer;
import com.uber.api.shared.constants.CustomerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByEmail(String email);
    Optional<Customer> findByEmailAndStatus(String email, CustomerStatus status);
}
