package com.qwerys.qwerys_backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Rutas que requieren autenticación JWT
                .requestMatchers(HttpMethod.GET,    "/api/auth/me").authenticated()
                .requestMatchers(HttpMethod.PUT,    "/api/auth/profile").authenticated()
                .requestMatchers(HttpMethod.PUT,    "/api/auth/change-password").authenticated()
                .requestMatchers(HttpMethod.PUT,    "/api/auth/change-email").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/auth/account").authenticated()
                .requestMatchers(HttpMethod.GET,    "/api/history/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/history/**").authenticated()
                .requestMatchers(HttpMethod.PATCH,  "/api/history/**").authenticated()
                // ai-supplement is under /api/history/{id}/ai-supplement — covered above
                // Solo indica si hay API key en el servidor (no expone la clave); debe funcionar sin JWT
                .requestMatchers(HttpMethod.GET,    "/api/ai/status").permitAll()
                .requestMatchers(HttpMethod.POST,   "/api/ai/**").authenticated()
                // Todo lo demás es público (invitados, WebSocket, queries, schema, etc.)
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://192.168.*:*"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
