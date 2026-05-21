package com.qwerys.qwerys_backend.model.ai;

public record AnalysisWarningDto(String code, String severity, String message) {
    public AnalysisWarningDto(String code, String severity) {
        this(code, severity, null);
    }
}
