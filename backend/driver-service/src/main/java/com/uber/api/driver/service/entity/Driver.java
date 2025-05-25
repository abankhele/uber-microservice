package com.uber.api.driver.service.entity;

import com.uber.api.shared.constants.DriverStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "drivers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    private String name;
    private String phone;
    private String licenseNumber;

    @Enumerated(EnumType.STRING)
    private DriverStatus status;

    private Double currentLatitude;
    private Double currentLongitude;
    private String currentCity;

    private UUID currentRideRequestId;

    @Version
    private Long version;

    // Business logic
    public double distanceToLocation(Double latitude, Double longitude) {
        if (currentLatitude == null || currentLongitude == null) {
            return Double.MAX_VALUE;
        }

        double lat1Rad = Math.toRadians(currentLatitude);
        double lat2Rad = Math.toRadians(latitude);
        double deltaLatRad = Math.toRadians(latitude - currentLatitude);
        double deltaLonRad = Math.toRadians(longitude - currentLongitude);

        double a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                        Math.sin(deltaLonRad / 2) * Math.sin(deltaLonRad / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return 6371 * c; // Distance in kilometers
    }
}
