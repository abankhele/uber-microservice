package com.uber.api.shared.entities;

import com.uber.api.shared.constants.RideStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "ride_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    private String customerEmail;
    private String driverEmail;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "pickup_location_id")
    private Location pickupLocation;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "destination_location_id")
    private Location destinationLocation;

    @Enumerated(EnumType.STRING)
    private RideStatus status;

    private BigDecimal estimatedPrice;
    private BigDecimal finalPrice;

    private ZonedDateTime createdAt;
    private ZonedDateTime completedAt;

    // Business logic
    public double calculateDistance() {
        if (pickupLocation == null || destinationLocation == null) {
            return 0.0;
        }
        return pickupLocation.distanceTo(destinationLocation);
    }

    public BigDecimal calculateEstimatedPrice() {
        double distance = calculateDistance();
        double basePrice = 5.0; // Base fare
        double pricePerKm = 2.0; // Price per kilometer

        return BigDecimal.valueOf(basePrice + (distance * pricePerKm));
    }
}
