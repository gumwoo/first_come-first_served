package com.flowticket.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PhoneRequest(
        @NotBlank @Pattern(regexp = "^01[0-9]{8,9}$") String phone
) {}
