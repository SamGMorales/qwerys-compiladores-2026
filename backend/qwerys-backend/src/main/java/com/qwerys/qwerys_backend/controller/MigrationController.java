package com.qwerys.qwerys_backend.controller;

import com.qwerys.qwerys_backend.migration.MigrationRequest;
import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/migration")
@CrossOrigin(origins = "http://localhost:4200")
public class MigrationController {

    private final MigrationService migrationService;

    public MigrationController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping("/convert")
    public ResponseEntity<MigrationResult> convert(@RequestBody MigrationRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(new MigrationResult(
                    false, "", List.of("Cuerpo de petición inválido"), List.of()));
        }
        MigrationResult result = migrationService.convert(
                request.sourceCode(),
                request.sourceLanguage(),
                request.targetLanguage());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/targets/{sourceLanguage}")
    public ResponseEntity<List<String>> getAvailableTargets(@PathVariable String sourceLanguage) {
        return ResponseEntity.ok(migrationService.getAvailableTargets(sourceLanguage));
    }
}
