package com.uber.api.customer.service.repository;

import com.uber.api.shared.entities.QueuedRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QueuedRequestRepository extends JpaRepository<QueuedRequest, UUID> {

    @Query("SELECT q FROM QueuedRequest q WHERE q.status = 'QUEUED' ORDER BY q.priority ASC, q.queuedAt ASC")
    List<QueuedRequest> findQueuedRequestsOrderedByPriority();

    List<QueuedRequest> findByStatusAndExpiresAtBefore(String status, ZonedDateTime expireTime);

    Optional<QueuedRequest> findByRideRequestId(UUID rideRequestId);

    void deleteByRideRequestId(UUID rideRequestId);

    @Query("SELECT COUNT(q) FROM QueuedRequest q WHERE q.status = 'QUEUED'")
    long countQueuedRequests();
}
