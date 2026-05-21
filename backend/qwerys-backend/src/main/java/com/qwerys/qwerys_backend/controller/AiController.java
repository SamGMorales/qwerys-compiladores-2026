package com.qwerys.qwerys_backend.controller;

import com.qwerys.qwerys_backend.model.ai.*;
import com.qwerys.qwerys_backend.service.AiSuggestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:64018"})
public class AiController {

    private final AiSuggestionService aiSuggestionService;

    public AiController(AiSuggestionService aiSuggestionService) {
        this.aiSuggestionService = aiSuggestionService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "available", aiSuggestionService.isAvailable()
        ));
    }

    @PostMapping("/suggest")
    public ResponseEntity<AiResponse> suggest(@RequestBody SuggestQueryRequest request) {
        return ResponseEntity.ok(aiSuggestionService.suggestQuery(
                request != null ? request : new SuggestQueryRequest("", "mysql", null, null)));
    }

    @PostMapping("/autocomplete")
    public ResponseEntity<AiResponse> autocomplete(@RequestBody AutocompleteRequest request) {
        return ResponseEntity.ok(aiSuggestionService.autocomplete(
                request != null ? request : new AutocompleteRequest("", "mysql", null, null)));
    }

    @PostMapping("/generate")
    public ResponseEntity<AiResponse> generate(@RequestBody GenerateQueryRequest request) {
        return ResponseEntity.ok(aiSuggestionService.generateQuery(
                request != null
                        ? request
                        : new GenerateQueryRequest("SELECT", "", null, "", "mysql", null)));
    }

    @PostMapping("/explain")
    public ResponseEntity<AiResponse> explain(@RequestBody ExplainErrorsRequest request) {
        return ResponseEntity.ok(aiSuggestionService.explainErrors(
                request != null ? request : new ExplainErrorsRequest("", "mysql", null, null)));
    }

    @PostMapping("/complement-analysis")
    public ResponseEntity<ComplementAnalysisResponse> complementAnalysis(
            @RequestBody ComplementAnalysisRequest request) {
        return ResponseEntity.ok(aiSuggestionService.complementAnalysis(
                request != null
                        ? request
                        : new ComplementAnalysisRequest(
                                "",
                                "mysql",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)));
    }

    @PostMapping("/improve-migration")
    public ResponseEntity<AiResponse> improveMigration(@RequestBody ImproveMigrationRequest request) {
        return ResponseEntity.ok(aiSuggestionService.improveMigration(
                request != null
                        ? request
                        : new ImproveMigrationRequest("", "", "", "", null, null, null)));
    }

    @PostMapping("/explain-security")
    public ResponseEntity<AiResponse> explainSecurity(@RequestBody ExplainSecurityRequest request) {
        return ResponseEntity.ok(aiSuggestionService.explainSecurityFinding(
                request != null
                        ? request
                        : new ExplainSecurityRequest("", "mysql", "", "", "", null)));
    }
}
