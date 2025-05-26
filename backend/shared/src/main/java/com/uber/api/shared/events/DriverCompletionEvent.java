package com.uber.api.shared.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverCompletionEvent {
    private String driverEmail;
    private UUID rideRequestId;
    private String customerEmail;
    private String status; // "COMPLETED" or "CANCELLED"
}
