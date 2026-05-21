package com.qwerys.qwerys_backend.model.dto;

public record AuthResponse(
        String token,
        String name,
        String email,
        Boolean darkTheme
) {}
