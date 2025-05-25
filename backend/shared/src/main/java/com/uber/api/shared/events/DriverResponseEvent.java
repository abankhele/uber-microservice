package com.uber.api.shared.events;

import com.uber.api.shared.constants.DriverStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverResponseEvent {
    private UUID sagaId;
    private UUID rideRequestId;
    private String driverEmail;
    private DriverStatus status;
    private boolean accepted;
    private String rejectionReason;
}
