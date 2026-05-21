package com.qwerys.qwerys_backend.model.dto;

public record ChangeEmailRequest(
        String newEmail,
        String currentPassword
) {}
