package com.uber.api.payment.service.entity;

import com.uber.api.shared.constants.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    private String customerEmail;
    private UUID rideRequestId;
    private UUID sagaId;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    private TransactionType type;

    private String description;
    private ZonedDateTime createdAt;
    private ZonedDateTime processedAt;

    @Version
    private Long version;

    public enum TransactionType {
        DEBIT, CREDIT, REFUND
    }
}
