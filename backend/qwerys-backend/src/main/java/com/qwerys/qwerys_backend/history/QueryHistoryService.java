package com.qwerys.qwerys_backend.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerys.qwerys_backend.model.QueryHistoryEntry;
import com.qwerys.qwerys_backend.model.User;
import com.qwerys.qwerys_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class QueryHistoryService {

    private static final Logger log = LoggerFactory.getLogger(QueryHistoryService.class);

    private final QueryHistoryRepository repository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persists an analysis snapshot for the authenticated user only.
     * Never throws — analysis must not fail because of history.
     */
    /**
     * @return saved entry id, or empty when not authenticated or on failure
     */
    public Optional<Long> save(
            String query,
            String databaseType,
            boolean valid,
            int errorCount,
            int warningCount,
            int optimizationCount,
            Object analysisResult,
            String analysisLocale) {
        try {
            Optional<Long> userId = currentUserId();
            if (userId.isEmpty()) {
                return Optional.empty();
            }
            String json = objectMapper.writeValueAsString(analysisResult);
            QueryHistoryEntry entry = QueryHistoryEntry.builder()
                    .userId(userId.get())
                    .query(query != null ? query : "")
                    .databaseType(databaseType != null ? databaseType : "")
                    .valid(valid)
                    .aiAssistedValid(null)
                    .aiProvider(null)
                    .analysisLocale(analysisLocale)
                    .errorCount(errorCount)
                    .warningCount(warningCount)
                    .optimizationCount(optimizationCount)
                    .analyzedAt(LocalDateTime.now())
                    .resultJson(json)
                    .favorite(false)
                    .build();
            return Optional.of(repository.save(entry).getId());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize query history result: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to save query history: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Records AI complement outcome on an existing history row (same user only).
     */
    public Optional<QueryHistoryEntry> applyAiSupplement(Long userId, Long entryId, AiHistorySupplementRequest req) {
        if (userId == null || entryId == null || req == null) {
            return Optional.empty();
        }
        Optional<QueryHistoryEntry> opt = repository.findByIdAndUserId(entryId, userId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        QueryHistoryEntry entry = opt.get();
        if (req.aiAssistedValid() != null) {
            entry.setAiAssistedValid(req.aiAssistedValid());
        }
        if (req.aiProvider() != null && !req.aiProvider().isBlank()) {
            entry.setAiProvider(req.aiProvider().trim());
        }
        if (req.analysisLocale() != null && !req.analysisLocale().isBlank()) {
            entry.setAnalysisLocale(req.analysisLocale().trim());
        }
        if (req.effectiveResultJson() != null && !req.effectiveResultJson().isBlank()) {
            entry.setResultJson(req.effectiveResultJson());
        }
        if (req.aiComplementJson() != null && !req.aiComplementJson().isBlank()) {
            entry.setAiComplementJson(req.aiComplementJson());
        }
        if (req.optimizationCount() != null) {
            entry.setOptimizationCount(Math.max(0, req.optimizationCount()));
        }
        if (req.warningCount() != null) {
            entry.setWarningCount(Math.max(0, req.warningCount()));
        }
        return Optional.of(repository.save(entry));
    }

    public Page<QueryHistoryEntry> getHistory(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
        return repository.findByUserIdOrderByAnalyzedAtDesc(userId, pageable);
    }

    public Optional<QueryHistoryEntry> getById(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId);
    }

    public void deleteById(Long userId, Long id) {
        repository.findByIdAndUserId(id, userId).ifPresent(repository::delete);
    }

    @Transactional
    public void deleteAll(Long userId) {
        repository.deleteByUserId(userId);
    }

    public Optional<QueryHistoryEntry> toggleFavorite(Long userId, Long id) {
        Optional<QueryHistoryEntry> opt = repository.findByIdAndUserId(id, userId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        QueryHistoryEntry entry = opt.get();
        entry.setFavorite(!entry.isFavorite());
        return Optional.of(repository.save(entry));
    }

    public List<QueryHistoryEntry> getFavorites(Long userId) {
        return repository.findByUserIdAndFavoriteTrueOrderByAnalyzedAtDesc(userId);
    }

    public List<QueryHistoryEntry> search(Long userId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return repository.findByUserIdAndQueryContainingIgnoreCaseOrderByAnalyzedAtDesc(
                userId, keyword.trim());
    }

    public List<QueryHistoryEntry> getValidOnly(Long userId) {
        return repository.findByUserIdAndValidTrueOrderByAnalyzedAtDesc(userId);
    }

    public List<QueryHistoryEntry> getInvalidOnly(Long userId) {
        return repository.findByUserIdAndValidFalseOrderByAnalyzedAtDesc(userId);
    }

    public Optional<Long> resolveUserId(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmailIgnoreCase(email).map(User::getId);
    }

    private Optional<Long> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof UserDetails userDetails)) {
            return Optional.empty();
        }
        return userRepository.findByEmailIgnoreCase(userDetails.getUsername()).map(User::getId);
    }
}
