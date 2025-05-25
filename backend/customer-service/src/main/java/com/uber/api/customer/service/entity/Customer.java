package com.uber.api.customer.service.entity;

import com.uber.api.shared.constants.CustomerStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.util.UUID;

@Entity
@Table(name = "customers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    private String name;
    private String phone;

    @Enumerated(EnumType.STRING)
    private CustomerStatus status;

    private UUID currentRideRequestId;

    @Version
    private Long version;
}
