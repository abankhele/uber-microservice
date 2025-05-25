package com.uber.api.shared.outbox;

import com.uber.api.shared.saga.SagaStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

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
