package com.uber.api.shared.events;

import com.uber.api.shared.entities.Location;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequestEvent {
    private UUID sagaId;
    private UUID rideRequestId;
    private String customerEmail;
    private BigDecimal amount;
    private String description;
    private Location pickupLocation;
    private Location destinationLocation;
}
