package com.qwerys.qwerys_backend.model.dto;

public record ChangePasswordRequest(
        String currentPassword,
        String newPassword
) {}
