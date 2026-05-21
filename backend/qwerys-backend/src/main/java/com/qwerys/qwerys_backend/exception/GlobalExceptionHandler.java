package com.qwerys.qwerys_backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central error handler for all REST controllers.
 * Converts exceptions into a consistent JSON error body:
 * { "error": "...", "message": "...", "timestamp": "..." }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnsupportedDatabaseException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedDatabase(
            UnsupportedDatabaseException ex) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errorBody("UnsupportedDatabaseException", ex.getMessage()));
    }

    @ExceptionHandler(QueryAnalysisException.class)
    public ResponseEntity<Map<String, String>> handleQueryAnalysis(
            QueryAnalysisException ex) {

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody("QueryAnalysisException", ex.getMessage()));
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUserNotFound(UsernameNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(errorBody("UsernameNotFoundException", ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(errorBody("BadCredentialsException", "Contraseña incorrecta"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(errorBody("IllegalArgumentException", ex.getMessage()));
    }

    /**
     * JSON inválido u omisión de cuerpo: para {@code POST /api/schema/connect} el contrato exige nunca 500 —
     * misma forma que un fallo de conexión ({@code success:false}).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(
            HttpServletRequest request,
            HttpMessageNotReadableException ex) {

        String uri = request.getRequestURI();
        if (uri != null && uri.endsWith("/api/schema/connect")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", Boolean.FALSE);
            body.put("message", "No se pudo conectar. Verifica los datos.");
            return ResponseEntity.ok(body);
        }
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "Cuerpo de la petición inválido o incompleto.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        ex.printStackTrace();
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("InternalServerError",
                        "An unexpected error occurred. Please try again later."));
    }

    private Map<String, String> errorBody(String error, String message) {
        return Map.of(
                "error", error,
                "message", message,
                "timestamp", Instant.now().toString()
        );
    }
}
