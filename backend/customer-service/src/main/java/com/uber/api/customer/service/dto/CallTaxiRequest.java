package com.uber.api.customer.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallTaxiRequest {

    @Email
    @NotBlank
    private String customerEmail;

    @NotNull
    private LocationDTO pickupLocation;

    @NotNull
    private LocationDTO destinationLocation;

    private String specialInstructions;
}
