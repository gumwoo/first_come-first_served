package com.flowticket.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken
) {}
