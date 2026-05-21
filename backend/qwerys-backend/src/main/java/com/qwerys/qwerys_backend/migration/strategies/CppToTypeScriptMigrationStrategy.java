package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationStrategy;
import org.springframework.stereotype.Component;

import static com.qwerys.qwerys_backend.migration.strategies.MigrationStrategySupport.*;

@Component
public class CppToTypeScriptMigrationStrategy implements MigrationStrategy {

    @Override
    public String getSourceLanguage() {
        return "CPP";
    }

    @Override
    public String getTargetLanguage() {
        return "TYPESCRIPT";
    }

    @Override
    public MigrationResult migrate(String sourceCode) {
        Context c = ctx();
        String code = sourceCode == null ? "" : sourceCode;

        code = replacePrintfSimple(code, "console.log");
        code = replaceAll(code, "(?i)printf\\s*\\([^)]+\\)", "console.log(\"\")");
        code = replaceAll(code, "(?i)\\bstruct\\s+(\\w+)", "interface $1");
        code = replaceAll(code, "(?m)^\\s*#define\\s+(\\w+)\\s+(.+)$", "const $1 = $2;");
        code = replaceAll(code, "(?i)int\\s+main\\s*\\([^)]*\\)", "function main(): void {");
        code = replaceAll(code, "\\bint\\b", "number");
        code = replaceAll(code, "\\bfloat\\b|\\bdouble\\b", "number");
        code = replaceAll(code, "\\bchar\\b", "string");
        if (code.contains("malloc") || code.contains("free")) {
            code = replaceAll(code, "(?i)\\bmalloc\\s*\\([^)]*\\)", "/* gc */");
            code = replaceAll(code, "(?i)\\bfree\\s*\\([^)]*\\)", "");
            c.manual("Revisa malloc/free — TypeScript usa garbage collection");
        }
        if (code.contains("*") || code.contains("->")) {
            c.warn("Los punteros no tienen equivalente en TypeScript");
        }

        c.manual("Convierte los bloques a módulos TypeScript con export/import");

        return c.ok(code);
    }
}
