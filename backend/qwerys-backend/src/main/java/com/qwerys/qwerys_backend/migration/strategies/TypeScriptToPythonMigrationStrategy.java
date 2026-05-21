package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationStrategy;
import org.springframework.stereotype.Component;

import static com.qwerys.qwerys_backend.migration.strategies.MigrationStrategySupport.*;

@Component
public class TypeScriptToPythonMigrationStrategy implements MigrationStrategy {

    @Override
    public String getSourceLanguage() {
        return "TYPESCRIPT";
    }

    @Override
    public String getTargetLanguage() {
        return "PYTHON";
    }

    @Override
    public MigrationResult migrate(String sourceCode) {
        Context c = ctx();
        String code = sourceCode == null ? "" : sourceCode;

        code = replaceAll(code, "console\\.log\\s*\\(", "print(");
        code = replaceAll(code, "\\bnull\\b|\\bundefined\\b", "None");
        code = replaceAll(code, "\\btrue\\b", "True");
        code = replaceAll(code, "\\bfalse\\b", "False");
        code = replaceAll(code, "`([^`]*)\\$\\{([^}]+)\\}([^`]*)`", "f\"$1{$2}$3\"");
        code = replaceAll(code, "(?i)\\bconst\\s+|\\blet\\s+", "");
        code = replaceAll(code, ":\\s*\\w+(?:<[^>]+>)?", "");
        code = replaceAll(code, "(?i)Array<[^>]+>", "list");
        code = replaceAll(code, "(?i)Map<[^>]+>", "dict");
        code = replaceAll(code, "(?i)Record<[^>]+>", "dict");
        code = replaceAll(code, "[{}]", "");

        c.manual("Convierte los bloques {} a indentación de 4 espacios");
        c.manual("Revisa las funciones asíncronas — usa async/await de Python o asyncio");

        return c.ok(code);
    }
}
