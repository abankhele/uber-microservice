package com.uber.api.driver.service.entity;

import com.uber.api.shared.outbox.OutboxStatus;
import com.uber.api.shared.saga.SagaStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "driver_outbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    private UUID sagaId;

    private String eventType;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    private OutboxStatus status;

    @Enumerated(EnumType.STRING)
    private SagaStatus sagaStatus;

    private ZonedDateTime createdAt;

    private ZonedDateTime processedAt;

    @Version
    private Long version;
}
