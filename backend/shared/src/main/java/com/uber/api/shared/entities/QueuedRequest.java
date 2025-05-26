package com.uber.api.shared.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "queued_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueuedRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    private UUID rideRequestId;
    private UUID sagaId;
    private String customerEmail;

    @Column(columnDefinition = "TEXT")
    private String driverRequestPayload;

    private ZonedDateTime queuedAt;
    private ZonedDateTime expiresAt;
    private String status; // QUEUED, PROCESSING, COMPLETED, EXPIRED, CANCELLED

    @Version
    private Long version;
}
