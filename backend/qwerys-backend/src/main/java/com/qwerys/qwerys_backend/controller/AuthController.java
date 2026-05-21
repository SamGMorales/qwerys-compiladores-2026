package com.qwerys.qwerys_backend.controller;

import com.qwerys.qwerys_backend.model.User;
import com.qwerys.qwerys_backend.model.dto.AccessibilityProfileRequest;
import com.qwerys.qwerys_backend.model.dto.AuthResponse;
import com.qwerys.qwerys_backend.model.dto.ChangeEmailRequest;
import com.qwerys.qwerys_backend.model.dto.ChangePasswordRequest;
import com.qwerys.qwerys_backend.model.dto.LoginRequest;
import com.qwerys.qwerys_backend.model.dto.RegisterRequest;
import com.qwerys.qwerys_backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import static java.util.Map.entry;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal UserDetails principal) {
        User user = authService.getByEmail(principal.getUsername());
        return ResponseEntity.ok(Map.ofEntries(
                entry("id",            user.getId()),
                entry("name",          user.getName()),
                entry("email",         user.getEmail()),
                entry("language",      user.getLanguage()),
                entry("darkTheme",     user.getDarkTheme()),
                entry("blindMode",     user.getBlindMode()),
                entry("lowVisionMode", user.getLowVisionMode()),
                entry("dyslexiaMode",  user.getDyslexiaMode()),
                entry("deafMode",      user.getDeafMode()),
                entry("adhdMode",      user.getAdhdMode()),
                entry("studentMode",   user.getStudentMode()),
                entry("expertMode",    user.getExpertMode()),
                entry("customDatabases", user.getCustomDatabases() != null ? user.getCustomDatabases() : List.of()),
                entry("createdAt",     user.getCreatedAt().toString())
        ));
    }

    @PutMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody AccessibilityProfileRequest request) {

        User updated = authService.updateProfile(principal.getUsername(), request);
        return ResponseEntity.ok(Map.ofEntries(
                entry("language",      updated.getLanguage()),
                entry("darkTheme",     updated.getDarkTheme()),
                entry("blindMode",     updated.getBlindMode()),
                entry("lowVisionMode", updated.getLowVisionMode()),
                entry("dyslexiaMode",  updated.getDyslexiaMode()),
                entry("deafMode",      updated.getDeafMode()),
                entry("adhdMode",      updated.getAdhdMode()),
                entry("studentMode",   updated.getStudentMode()),
                entry("expertMode",    updated.getExpertMode()),
                entry("customDatabases", updated.getCustomDatabases() != null ? updated.getCustomDatabases() : List.of())
        ));
    }

    @PutMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            authService.changePassword(userDetails.getUsername(), request);
            return ResponseEntity.ok(Map.of("message", "Contraseña actualizada correctamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/change-email")
    public ResponseEntity<?> changeEmail(
            @RequestBody ChangeEmailRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            authService.changeEmail(userDetails.getUsername(), request);
            return ResponseEntity.ok(Map.of("message", "Correo actualizado correctamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/account")
    public ResponseEntity<?> deleteAccount(
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            authService.deleteAccount(userDetails.getUsername());
            return ResponseEntity.ok(Map.of("message", "Cuenta eliminada correctamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
