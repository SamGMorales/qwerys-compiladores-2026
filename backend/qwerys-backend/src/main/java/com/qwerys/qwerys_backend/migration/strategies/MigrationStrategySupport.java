package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers for best-effort text migrations (regex-based, not AST). */
final class MigrationStrategySupport {

    private MigrationStrategySupport() {}

    static final class Context {
        private final List<String> warnings = new ArrayList<>();
        private final List<String> manualSteps = new ArrayList<>();

        void warn(String message) {
            if (message != null && !message.isBlank() && !warnings.contains(message)) {
                warnings.add(message);
            }
        }

        void manual(String step) {
            if (step != null && !step.isBlank() && !manualSteps.contains(step)) {
                manualSteps.add(step);
            }
        }

        MigrationResult ok(String migratedCode) {
            return new MigrationResult(true, migratedCode, List.copyOf(warnings), List.copyOf(manualSteps));
        }
    }

    static Context ctx() {
        return new Context();
    }

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

    static String replaceAll(String input, String regex, String replacement) {
        return Pattern.compile(regex, Pattern.MULTILINE).matcher(input).replaceAll(replacement);
    }

    static String replacePrintfSimple(String code, String printlnPrefix) {
        Matcher m = Pattern.compile("(?i)printf\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)").matcher(code);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String literal = m.group(1).replace("\\n", "\n");
            m.appendReplacement(sb, Matcher.quoteReplacement(printlnPrefix + "(\"" + literal + "\")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String stripCppTypes(String code) {
        return replaceAll(code, "(?m)^\\s*(?:const\\s+)?(?:unsigned\\s+)?(?:signed\\s+)?(?:int|float|double|char|long|short|void)\\s+\\*?\\s*", "");
    }
}
