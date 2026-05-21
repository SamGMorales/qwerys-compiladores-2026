package com.qwerys.qwerys_backend.student;

import com.qwerys.qwerys_backend.dto.StudentExplanationDto;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Locale-aware catalog of educational explanations keyed by analysis error code.
 * Content is aligned with frontend i18n ({@code analyzer.issues.*} / {@code student.explanations.*}).
 */
@Component
public class StudentExplanationRegistry {

    /** Same aliases as {@code StudentExplanationsService.CODE_ALIASES} on the frontend. */
    private static final Map<String, String> CODE_ALIASES = Map.ofEntries(
            Map.entry("SEM-DELETE-NOWHERE", "SEM-001"),
            Map.entry("SEM-UPDATE-NOWHERE", "SEM-002"),
            Map.entry("SEM-SELECT-COLS", "SE001"),
            Map.entry("SEM-FROM-MISSING", "SE002"),
            Map.entry("SEM-WHERE-INCOMPLETE", "SE003"),
            Map.entry("SQL-INJECTION", "SE007"),
            Map.entry("SQL-INJ", "SE007"),
            Map.entry("REDIS-KEYS-STAR", "RDS-KEYS-001"),
            Map.entry("REDIS-KEYS", "RDS-KEYS-001"),
            Map.entry("REDIS-KEYS-PATTERN", "RDS-KEYS-002"),
            Map.entry("MGO-001", "MGO-NOFILTER-001")
    );

    private final Map<String, StudentExplanationDto> spanish;
    private final Map<String, StudentExplanationDto> english;

    public StudentExplanationRegistry() {
        spanish = loadBundle("es");
        english = loadBundle("en");
    }

    public StudentExplanationDto lookup(String code, Locale locale) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.strip();
        Map<String, StudentExplanationDto> bundle = isSpanish(locale) ? spanish : english;
        StudentExplanationDto hit = bundle.get(trimmed);
        if (hit != null) {
            return hit;
        }
        String alias = CODE_ALIASES.get(trimmed);
        if (alias != null) {
            return bundle.get(alias);
        }
        return null;
    }

    private static boolean isSpanish(Locale locale) {
        return locale != null && locale.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
    }

    private static Map<String, StudentExplanationDto> loadBundle(String lang) {
        String path = "student/explanations_" + lang + ".properties";
        Properties props = new Properties();
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return Map.of();
            }
            try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        } catch (Exception e) {
            return Map.of();
        }

        Map<String, String> what = new HashMap<>();
        Map<String, String> why = new HashMap<>();
        Map<String, String> example = new HashMap<>();
        Map<String, String> corrected = new HashMap<>();

        for (String key : props.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            String code = key.substring(0, dot);
            String field = key.substring(dot + 1);
            String value = props.getProperty(key);
            switch (field) {
                case "what" -> what.put(code, value);
                case "why" -> why.put(code, value);
                case "example" -> example.put(code, value);
                case "correctedExample" -> corrected.put(code, value);
                default -> { }
            }
        }

        Map<String, StudentExplanationDto> out = new HashMap<>();
        for (String code : what.keySet()) {
            putIfPresent(out, code, build(what, why, example, corrected, code));
        }
        for (String code : why.keySet()) {
            putIfPresent(out, code, build(what, why, example, corrected, code));
        }
        for (String code : corrected.keySet()) {
            putIfPresent(out, code, build(what, why, example, corrected, code));
        }
        return Map.copyOf(out);
    }

    private static void putIfPresent(
            Map<String, StudentExplanationDto> out, String code, StudentExplanationDto dto) {
        if (dto != null) {
            out.putIfAbsent(code, dto);
        }
    }

    private static StudentExplanationDto build(
            Map<String, String> what,
            Map<String, String> why,
            Map<String, String> example,
            Map<String, String> corrected,
            String code) {
        String w = what.get(code);
        String y = why.get(code);
        String ex = example.get(code);
        String corr = corrected.get(code);
        if ((w == null || w.isBlank()) && (y == null || y.isBlank())) {
            return null;
        }
        return new StudentExplanationDto(
                blankToNull(w),
                blankToNull(y),
                blankToNull(ex),
                blankToNull(corr));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
