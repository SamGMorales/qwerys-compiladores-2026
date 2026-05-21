package com.qwerys.qwerys_backend.controller;

import com.qwerys.qwerys_backend.adapter.DatabaseConfig;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.exception.UnsupportedDatabaseException;
import com.qwerys.qwerys_backend.model.dto.SchemaDescribeRequest;
import com.qwerys.qwerys_backend.service.SchemaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schema")
@CrossOrigin(origins = "http://localhost:4200")
public class SchemaController {

    private static final String CONNECT_FAIL_MESSAGE = "No se pudo conectar. Verifica los datos.";

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @PostMapping("/connect")
    public ResponseEntity<?> connect(@RequestBody(required = false) DatabaseConfig config) {
        try {
            if (config == null) {
                return ResponseEntity.ok(connectFailureBody());
            }
            boolean ok = schemaService.testConnection(config);
            String message = ok ? "Conexión exitosa" : CONNECT_FAIL_MESSAGE;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", ok);
            body.put("message", message);
            return ResponseEntity.ok(body);
        } catch (UnsupportedDatabaseException e) {
            String detail = e.getMessage() != null ? e.getMessage() : "Motor no soportado";
            return ResponseEntity.badRequest().body(Map.of("error", detail));
        } catch (Exception e) {
            return ResponseEntity.ok(connectFailureBody());
        }
    }

    private static Map<String, Object> connectFailureBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", Boolean.FALSE);
        body.put("message", CONNECT_FAIL_MESSAGE);
        return body;
    }

    @PostMapping("/tables")
    public ResponseEntity<?> tables(@RequestBody DatabaseConfig config) {
        try {
            List<String> names = schemaService.getTableNames(config);
            return ResponseEntity.ok(names);
        } catch (ResponseStatusException e) {
            return errorFromStatusException(e);
        } catch (Exception e) {
            return serviceUnavailable(e.getMessage());
        }
    }

    @PostMapping("/describe")
    public ResponseEntity<?> describe(@RequestBody SchemaDescribeRequest request) {
        try {
            if (request == null || request.connection() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Se requiere el objeto connection"));
            }
            TableSchema table = schemaService.describeTable(request.connection(), request.tableName());
            return ResponseEntity.ok(table);
        } catch (ResponseStatusException e) {
            return errorFromStatusException(e);
        } catch (Exception e) {
            return serviceUnavailable(e.getMessage());
        }
    }

    @PostMapping("/full")
    public ResponseEntity<?> full(@RequestBody DatabaseConfig config) {
        try {
            DatabaseSchema schema = schemaService.getFullSchema(config);
            return ResponseEntity.ok(schema);
        } catch (ResponseStatusException e) {
            return errorFromStatusException(e);
        } catch (Exception e) {
            return serviceUnavailable(e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, String>> errorFromStatusException(ResponseStatusException e) {
        HttpStatusCode status = e.getStatusCode();
        String msg = e.getReason() != null ? e.getReason() : status.toString();
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }

    private static ResponseEntity<Map<String, String>> serviceUnavailable(String detail) {
        String msg = detail != null && !detail.isBlank()
                ? detail
                : "Error temporal al contactar el motor. Inténtalo de nuevo.";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", msg));
    }
}
