package com.uber.api.customer.service.repository;

import com.uber.api.shared.entities.RideRequest;
import com.uber.api.shared.constants.RideStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RideRequestRepository extends JpaRepository<RideRequest, UUID> {
    Optional<RideRequest> findByIdAndCustomerEmail(UUID id, String customerEmail);
    List<RideRequest> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);
    Optional<RideRequest> findByCustomerEmailAndStatus(String customerEmail, RideStatus status);
}
