package com.qwerys.qwerys_backend.controller;

import com.qwerys.qwerys_backend.history.AiHistorySupplementRequest;
import com.qwerys.qwerys_backend.history.QueryHistoryService;
import com.qwerys.qwerys_backend.model.QueryHistoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/history")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:64018"})
@RequiredArgsConstructor
public class QueryHistoryController {

    private final QueryHistoryService historyService;

    @GetMapping
    public ResponseEntity<Page<QueryHistoryEntry>> list(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return resolveUser(principal)
                .map(userId -> ResponseEntity.ok(historyService.getHistory(userId, page, size)))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<QueryHistoryEntry> getById(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id) {
        return resolveUser(principal)
                .flatMap(userId -> historyService.getById(userId, id)
                        .map(ResponseEntity::ok)
                        .or(() -> java.util.Optional.of(ResponseEntity.notFound().build())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteById(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id) {
        return resolveUser(principal)
                .map(userId -> {
                    historyService.deleteById(userId, id);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @PatchMapping("/{id}/ai-supplement")
    public ResponseEntity<QueryHistoryEntry> applyAiSupplement(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @RequestBody AiHistorySupplementRequest body) {
        return resolveUser(principal)
                .flatMap(userId -> historyService.applyAiSupplement(userId, id, body)
                        .map(ResponseEntity::ok)
                        .or(() -> java.util.Optional.of(ResponseEntity.notFound().build())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @PatchMapping("/{id}/favorite")
    public ResponseEntity<QueryHistoryEntry> toggleFavorite(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id) {
        return resolveUser(principal)
                .flatMap(userId -> historyService.toggleFavorite(userId, id)
                        .map(ResponseEntity::ok)
                        .or(() -> java.util.Optional.of(ResponseEntity.notFound().build())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @GetMapping("/favorites")
    public ResponseEntity<List<QueryHistoryEntry>> favorites(
            @AuthenticationPrincipal UserDetails principal) {
        return resolveUser(principal)
                .map(userId -> ResponseEntity.ok(historyService.getFavorites(userId)))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @GetMapping("/search")
    public ResponseEntity<List<QueryHistoryEntry>> search(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam String keyword) {
        return resolveUser(principal)
                .map(userId -> ResponseEntity.ok(historyService.search(userId, keyword)))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @GetMapping("/valid")
    public ResponseEntity<List<QueryHistoryEntry>> validOnly(
            @AuthenticationPrincipal UserDetails principal) {
        return resolveUser(principal)
                .map(userId -> ResponseEntity.ok(historyService.getValidOnly(userId)))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @GetMapping("/invalid")
    public ResponseEntity<List<QueryHistoryEntry>> invalidOnly(
            @AuthenticationPrincipal UserDetails principal) {
        return resolveUser(principal)
                .map(userId -> ResponseEntity.ok(historyService.getInvalidOnly(userId)))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll(@AuthenticationPrincipal UserDetails principal) {
        return resolveUser(principal)
                .map(userId -> {
                    historyService.deleteAll(userId);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    private java.util.Optional<Long> resolveUser(UserDetails principal) {
        if (principal == null) {
            return java.util.Optional.empty();
        }
        return historyService.resolveUserId(principal.getUsername());
    }
}
