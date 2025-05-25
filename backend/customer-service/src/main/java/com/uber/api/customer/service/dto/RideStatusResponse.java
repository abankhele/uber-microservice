package com.uber.api.customer.service.dto;

import com.uber.api.shared.constants.RideStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideStatusResponse {

    private UUID rideRequestId;
    private RideStatus status;
    private String customerEmail;
    private String driverEmail;
    private BigDecimal estimatedPrice;
    private BigDecimal finalPrice;
    private ZonedDateTime createdAt;
    private String statusMessage;
}
