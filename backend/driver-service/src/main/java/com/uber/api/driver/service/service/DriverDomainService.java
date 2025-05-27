package com.uber.api.driver.service.service;

import com.uber.api.shared.events.DriverRequestEvent;
import com.uber.api.shared.events.DriverResponseEvent;

public interface DriverDomainService {
    DriverResponseEvent assignDriver(DriverRequestEvent driverRequest);
    void updateDriverStatus(String driverEmail, com.uber.api.shared.constants.DriverStatus status);
    void updateDriverLocation(String driverEmail, Double latitude, Double longitude, String city);
    void resetAllDriversToAvailable();
    void completeDriverRide(String driverEmail);
    int getAvailableDriverCount();
    boolean hasAvailableDrivers();
    int getBusyDriverCount();
    void processDriverAssignment(DriverRequestEvent driverRequest);

}
