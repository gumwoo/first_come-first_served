package com.flowticket.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PhoneVerifyRequest(
        @NotBlank @Pattern(regexp = "^01[0-9]{8,9}$") String phone,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code
) {}
