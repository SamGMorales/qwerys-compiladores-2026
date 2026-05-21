package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationStrategy;
import org.springframework.stereotype.Component;

import static com.qwerys.qwerys_backend.migration.strategies.MigrationStrategySupport.*;

@Component
public class PythonToTypeScriptMigrationStrategy implements MigrationStrategy {

    @Override
    public String getSourceLanguage() {
        return "PYTHON";
    }

    @Override
    public String getTargetLanguage() {
        return "TYPESCRIPT";
    }

    @Override
    public MigrationResult migrate(String sourceCode) {
        Context c = ctx();
        String code = sourceCode == null ? "" : sourceCode;

        code = replaceAll(code, "(?m)^\\s*print\\s*\\(", "console.log(");
        code = replaceAll(code, "\\bNone\\b", "null");
        code = replaceAll(code, "\\bTrue\\b", "true");
        code = replaceAll(code, "\\bFalse\\b", "false");
        code = replaceAll(code, "(?m)^\\s*def\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*:", "function $1($2): void {");
        code = replaceAll(code, "f\"([^\"]*)\"", "`$1`");
        code = replaceAll(code, "\\{([^}]+)\\}", "${$1}");
        code = replaceAll(code, "(\\w+)\\s*=\\s*\\[\\s*\\]", "const $1: Array<any> = []");
        code = replaceAll(code, "(\\w+)\\s*=\\s*\\{\\s*\\}", "const $1: Record<string, any> = {}");
        code = replaceAll(code, "(?m)(\\w+)\\s*:\\s*$", "$1 {");

        c.manual("Añade tipos explícitos a parámetros y retornos de funciones");
        c.manual("Convierte la indentación Python a bloques {} TypeScript");

        return c.ok(code);
    }
}
