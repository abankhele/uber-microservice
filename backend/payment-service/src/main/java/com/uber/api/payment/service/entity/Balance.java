package com.uber.api.payment.service.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Entity
@Table(name = "balances")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Balance {

    @Id
    private String customerEmail;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    private ZonedDateTime lastUpdated;

    @Version
    private Long version;

    // Business logic
    public boolean hasSufficientBalance(BigDecimal requiredAmount) {
        return amount.compareTo(requiredAmount) >= 0;
    }

    public void deductAmount(BigDecimal amountToDeduct) {
        if (!hasSufficientBalance(amountToDeduct)) {
            throw new RuntimeException("Insufficient balance");
        }
        this.amount = this.amount.subtract(amountToDeduct);
        this.lastUpdated = ZonedDateTime.now();
    }

    public void addAmount(BigDecimal amountToAdd) {
        this.amount = this.amount.add(amountToAdd);
        this.lastUpdated = ZonedDateTime.now();
    }
}
