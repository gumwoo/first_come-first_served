package com.flowticket.auth.dto;

import com.flowticket.auth.domain.User;

public record MeResponse(
        Long id,
        String email,
        String name,
        String role,
        String provider
) {
    public static MeResponse from(User user) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getProvider().name());
    }
}
