package com.uber.api.driver.service.repository;

import com.uber.api.driver.service.entity.Driver;
import com.uber.api.shared.constants.DriverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DriverRepository extends JpaRepository<Driver, UUID> {
    Optional<Driver> findByEmail(String email);
    List<Driver> findByStatus(DriverStatus status);

    @Query("SELECT d FROM Driver d WHERE d.status = 'AVAILABLE' AND d.currentCity = :city")
    List<Driver> findAvailableDriversInCity(@Param("city") String city);

    @Query("SELECT d FROM Driver d WHERE d.status = 'AVAILABLE' ORDER BY d.id")
    List<Driver> findAllAvailableDrivers();
    long countByStatus(DriverStatus status);

}
