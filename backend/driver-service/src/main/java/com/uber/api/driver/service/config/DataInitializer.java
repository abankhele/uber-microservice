package com.uber.api.driver.service.config;

import com.uber.api.driver.service.entity.Driver;
import com.uber.api.driver.service.repository.DriverRepository;
import com.uber.api.shared.constants.DriverStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final DriverRepository driverRepository;

    @Override
    public void run(String... args) throws Exception {
        if (driverRepository.count() == 0) {
            createSampleDrivers();
        }
    }

    private void createSampleDrivers() {
        log.info("Creating sample drivers...");

        Driver driver1 = Driver.builder()
                .email("john.driver@uber.com")
                .name("John Driver")
                .phone("+1-555-0101")
                .licenseNumber("DL123456")
                .status(DriverStatus.AVAILABLE)
                .currentLatitude(40.7128)
                .currentLongitude(-74.0060)
                .currentCity("New York")
                .build();

        Driver driver2 = Driver.builder()
                .email("jane.driver@uber.com")
                .name("Jane Driver")
                .phone("+1-555-0102")
                .licenseNumber("DL789012")
                .status(DriverStatus.AVAILABLE)
                .currentLatitude(40.7589)
                .currentLongitude(-73.9851)
                .currentCity("New York")
                .build();

        Driver driver3 = Driver.builder()
                .email("mike.driver@uber.com")
                .name("Mike Driver")
                .phone("+1-555-0103")
                .licenseNumber("DL345678")
                .status(DriverStatus.AVAILABLE)
                .currentLatitude(40.7505)
                .currentLongitude(-73.9934)
                .currentCity("New York")
                .build();

        driverRepository.save(driver1);
        driverRepository.save(driver2);
        driverRepository.save(driver3);

        log.info("Created 3 sample drivers");
    }
}
