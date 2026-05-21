package com.qwerys.qwerys_backend.migration;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MigrationService {

    private final Map<String, MigrationStrategy> strategies;

    public MigrationService(List<MigrationStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        s -> strategyKey(s.getSourceLanguage(), s.getTargetLanguage()),
                        s -> s,
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate migration strategy: " + a.getSourceLanguage()
                                            + " -> " + a.getTargetLanguage());
                        }));
    }

    public MigrationResult convert(String sourceCode, String sourceLanguage, String targetLanguage) {
        String src = normalizeLanguage(sourceLanguage);
        String tgt = normalizeLanguage(targetLanguage);
        if (src.isEmpty() || tgt.isEmpty()) {
            return new MigrationResult(
                    false,
                    "",
                    List.of("sourceLanguage y targetLanguage son obligatorios"),
                    List.of());
        }
        MigrationStrategy strategy = strategies.get(strategyKey(src, tgt));
        if (strategy == null) {
            return new MigrationResult(
                    false,
                    "",
                    List.of("Par de lenguajes no soportado: " + src + " → " + tgt),
                    List.of());
        }
        return strategy.migrate(sourceCode);
    }

    public List<String> getAvailableTargets(String sourceLanguage) {
        String src = normalizeLanguage(sourceLanguage);
        if (src.isEmpty()) {
            return List.of();
        }
        String prefix = src + "_";
        return strategies.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String strategyKey(String sourceLanguage, String targetLanguage) {
        return normalizeLanguage(sourceLanguage) + "_" + normalizeLanguage(targetLanguage);
    }

    /** Aligns with frontend ids: CPP for C++, legacy alias C accepted. */
    static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "";
        }
        String upper = language.trim().toUpperCase();
        if ("C".equals(upper) || "C++".equals(upper) || "CPP".equals(upper)) {
            return "CPP";
        }
        return upper;
    }
}
