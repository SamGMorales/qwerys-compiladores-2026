package com.qwerys.qwerys_backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "query_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owner; null only for legacy rows — new saves always set user id. */
    @Column(name = "user_id")
    private Long userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String query;

    @Column(nullable = false)
    private String databaseType;

    /** Native analyzer validity (unchanged semantics for existing rows). */
    @Column(nullable = false)
    private boolean valid;

    /**
     * Effective validity after AI second pass, when recorded.
     * {@code null} = complement not run or not saved yet.
     */
    @Column(name = "ai_assisted_valid")
    private Boolean aiAssistedValid;

    /** e.g. groq:llama-3.3-70b-versatile or openrouter:meta-llama/... or rule-based */
    @Column(name = "ai_provider", length = 128)
    private String aiProvider;

    /** UI locale at analysis time (es, en). */
    @Column(name = "analysis_locale", length = 8)
    private String analysisLocale;

    @Column(nullable = false)
    private int errorCount;

    @Column(nullable = false)
    private int warningCount;

    @Column(nullable = false)
    private int optimizationCount;

    @Column(nullable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime analyzedAt;

    @Column(columnDefinition = "TEXT")
    private String resultJson;

    /** Serialized {@link com.qwerys.qwerys_backend.model.ai.ComplementAnalysisResponse}-like JSON for history UI. */
    @Column(name = "ai_complement_json", columnDefinition = "TEXT")
    private String aiComplementJson;

    @Column(nullable = false)
    @Builder.Default
    private boolean favorite = false;

    public QueryHistoryEntry(
            Long userId,
            String query,
            String databaseType,
            boolean valid,
            int errorCount,
            int warningCount,
            int optimizationCount,
            LocalDateTime analyzedAt,
            String resultJson,
            boolean favorite) {
        this.userId = userId;
        this.query = query;
        this.databaseType = databaseType;
        this.valid = valid;
        this.errorCount = errorCount;
        this.warningCount = warningCount;
        this.optimizationCount = optimizationCount;
        this.analyzedAt = analyzedAt;
        this.resultJson = resultJson;
        this.favorite = favorite;
    }
}
