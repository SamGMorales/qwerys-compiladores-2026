package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationStrategy;
import org.springframework.stereotype.Component;

import static com.qwerys.qwerys_backend.migration.strategies.MigrationStrategySupport.*;

@Component
public class CppToPythonMigrationStrategy implements MigrationStrategy {

    @Override
    public String getSourceLanguage() {
        return "CPP";
    }

    @Override
    public String getTargetLanguage() {
        return "PYTHON";
    }

    @Override
    public MigrationResult migrate(String sourceCode) {
        Context c = ctx();
        String code = sourceCode == null ? "" : sourceCode;

        code = replacePrintfSimple(code, "print");
        code = replaceAll(code, "(?i)printf\\s*\\([^)]+\\)", "print(\"\")");
        if (code.contains("#include")) {
            code = replaceAll(code, "(?m)^\\s*#include\\s*<([^>]+)>", "# import from <$1>");
            code = replaceAll(code, "(?m)^\\s*#include\\s*\"([^\"]+)\"", "# import from \"$1\"");
            c.warn("Busca el equivalente en la librería estándar de Python");
        }
        code = replaceAll(
                code,
                "(?i)int\\s+main\\s*\\([^)]*\\)",
                "if __name__ == '__main__':");
        code = replaceAll(
                code,
                "(?i)for\\s*\\(\\s*int\\s+(\\w+)\\s*=\\s*0\\s*;\\s*\\1\\s*<\\s*(\\w+)\\s*;\\s*\\1\\+\\+\\s*\\)",
                "for $1 in range($2):");
        code = stripCppTypes(code);
        if (code.contains("*") || code.contains("->")) {
            c.warn("Los punteros no tienen equivalente directo en Python");
        }

        c.manual("Convierte los bloques {} a indentación de 4 espacios");

        return c.ok(code);
    }
}
