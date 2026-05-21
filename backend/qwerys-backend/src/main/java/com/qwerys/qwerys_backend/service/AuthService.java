package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.config.JwtUtil;
import com.qwerys.qwerys_backend.model.User;
import com.qwerys.qwerys_backend.model.dto.AccessibilityProfileRequest;
import com.qwerys.qwerys_backend.model.dto.AuthResponse;
import com.qwerys.qwerys_backend.model.dto.ChangeEmailRequest;
import com.qwerys.qwerys_backend.model.dto.ChangePasswordRequest;
import com.qwerys.qwerys_backend.model.dto.LoginRequest;
import com.qwerys.qwerys_backend.model.dto.RegisterRequest;
import com.qwerys.qwerys_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    // ─── UserDetailsService ────────────────────────────────────────────────────

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + email));

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                Collections.emptyList()
        );
    }

    // ─── Registro ─────────────────────────────────────────────────────────────

    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new IllegalArgumentException("El email ya está registrado");
        }

        User user = User.builder()
                .name(request.name().trim())
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .build();

        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(token, user.getName(), user.getEmail(), false);
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
                .orElseThrow(() -> new UsernameNotFoundException("No existe ninguna cuenta con este correo"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(token, user.getName(), user.getEmail(),
                user.getDarkTheme() != null ? user.getDarkTheme() : false);
    }

    // ─── Obtener usuario autenticado ──────────────────────────────────────────

    public User getByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + email));
    }

    // ─── Actualizar perfil de accesibilidad ───────────────────────────────────

    public User updateProfile(String email, AccessibilityProfileRequest request) {
        User user = getByEmail(email);

        if (request.language()      != null) user.setLanguage(request.language());
        if (request.darkTheme()     != null) user.setDarkTheme(request.darkTheme());
        if (request.blindMode()     != null) user.setBlindMode(request.blindMode());
        if (request.lowVisionMode() != null) user.setLowVisionMode(request.lowVisionMode());
        if (request.dyslexiaMode()  != null) user.setDyslexiaMode(request.dyslexiaMode());
        if (request.deafMode()      != null) user.setDeafMode(request.deafMode());
        if (request.adhdMode()      != null) user.setAdhdMode(request.adhdMode());
        if (request.studentMode()   != null) user.setStudentMode(request.studentMode());
        if (request.expertMode()    != null) user.setExpertMode(request.expertMode());
        if (request.customDatabases() != null) {
            user.setCustomDatabases(new ArrayList<>(request.customDatabases()));
        }

        return userRepository.save(user);
    }

    public void changePassword(String userEmail, ChangePasswordRequest req) {
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(userEmail))
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        if (req.currentPassword() == null
                || !passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
            throw new RuntimeException("Contraseña actual incorrecta");
        }
        if (req.newPassword() == null || req.newPassword().length() < 6) {
            throw new RuntimeException("La nueva contraseña debe tener al menos 6 caracteres");
        }
        user.setPassword(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);
    }

    public void changeEmail(String userEmail, ChangeEmailRequest req) {
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(userEmail))
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        if (req.currentPassword() == null
                || !passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
            throw new RuntimeException("Contraseña incorrecta");
        }
        if (req.newEmail() == null || req.newEmail().isBlank()) {
            throw new RuntimeException("El nuevo correo es obligatorio");
        }
        String normalized = normalizeEmail(req.newEmail());
        if (user.getEmail().equalsIgnoreCase(normalized)) {
            return;
        }
        if (userRepository.existsByEmailIgnoreCase(normalized)) {
            throw new RuntimeException("El correo ya está en uso por otra cuenta");
        }
        user.setEmail(normalized);
        userRepository.save(user);
    }

    public void deleteAccount(String userEmail) {
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(userEmail))
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        userRepository.delete(user);
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
