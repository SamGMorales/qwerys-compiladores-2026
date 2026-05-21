package com.qwerys.qwerys_backend.analyzer;

import java.util.Locale;

/**
 * Shared UI-locale helpers for analyzer messages (English vs Spanish).
 */
public final class AnalysisMessages {

    private AnalysisMessages() {
    }

    public static boolean spanish(Locale ui) {
        return ui != null && ui.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
    }

    public static String t(Locale ui, String en, String es) {
        return spanish(ui) ? es : en;
    }
}
