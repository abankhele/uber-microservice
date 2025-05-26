package com.uber.api.customer.service.service;

import com.uber.api.customer.service.dto.CallTaxiRequest;
import com.uber.api.customer.service.dto.RideStatusResponse;
import com.uber.api.shared.entities.RideRequest;

public interface CustomerDomainService {
    RideStatusResponse callTaxi(CallTaxiRequest request);
    RideStatusResponse getRideStatus(String customerEmail);
    void completeRide(String customerEmail);
    void startRide(String customerEmail);
    void cancelRide(String customerEmail);
    void processExpiredRequests();
    void processQueuedRequests();
    void debugQueueOrder();

    // **NEW METHODS for RideMatchingService integration**
    RideRequest createRideRequestFromRequest(CallTaxiRequest request);
    void addToExistingQueue(RideRequest rideRequest);
    void startSagaForRide(RideRequest rideRequest);
}
