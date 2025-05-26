package com.uber.api.shared.constants;

public enum RideStatus {
    CREATED,           // Initial state
    PAYMENT_PROCESSING, // Payment in progress
    PAYMENT_COMPLETED,  // Payment successful
    DRIVER_SEARCHING,   // Looking for driver
    DRIVER_ASSIGNED,    // Driver found and assigned
    RIDE_STARTED,       // Ride in progress
    RIDE_COMPLETED,     // Ride finished
    PAYMENT_FAILED,     // Payment processing failed
    DRIVER_UNAVAILABLE, // No driver available
    CANCELLED,          // Ride cancelled by customer
    EXPIRED             // Request expired after timeout
}
