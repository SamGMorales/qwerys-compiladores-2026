package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationStrategy;
import org.springframework.stereotype.Component;

import static com.qwerys.qwerys_backend.migration.strategies.MigrationStrategySupport.*;

@Component
public class CppToJavaMigrationStrategy implements MigrationStrategy {

    @Override
    public String getSourceLanguage() {
        return "CPP";
    }

    @Override
    public String getTargetLanguage() {
        return "JAVA";
    }

    @Override
    public MigrationResult migrate(String sourceCode) {
        Context c = ctx();
        String code = sourceCode == null ? "" : sourceCode;

        if (code.contains("malloc") || code.contains("free")) {
            code = replaceAll(code, "(?i)\\bmalloc\\s*\\([^)]*\\)", "new Object()");
            code = replaceAll(code, "(?i)\\bfree\\s*\\([^)]*\\)", "");
            c.warn("Gestión de memoria manual no tiene equivalente directo en Java");
        }
        if (code.contains("#include")) {
            code = replaceAll(code, "(?m)^\\s*#include\\s*<([^>]+)>", "import $1;");
            code = replaceAll(code, "(?m)^\\s*#include\\s*\"([^\"]+)\"", "import $1;");
            c.warn("Revisa los imports equivalentes en Java");
        }
        if (code.contains("*") || code.contains("->")) {
            c.warn("Revisa punteros y referencias: conviértelos a referencias o colecciones de Java");
        }

        code = replacePrintfSimple(code, "System.out.println");
        code = replaceAll(code, "(?i)printf\\s*\\([^)]+\\)", "System.out.println(\"\")");
        code = replaceAll(
                code,
                "(?i)int\\s+main\\s*\\(\\s*int\\s+argc\\s*,\\s*char\\s*\\*\\s*argv\\s*\\[\\s*\\]\\s*\\)",
                "public static void main(String[] args)");
        code = replaceAll(code, "(?i)int\\s+main\\s*\\(\\s*\\)", "public static void main(String[] args)");
        code = replaceAll(code, "(?i)\\bstruct\\s+(\\w+)", "class $1");
        code = replaceAll(code, "(?m)^\\s*#define\\s+(\\w+)\\s+(.+)$", "static final $1 = $2;");
        code = replaceAll(code, "\\bNULL\\b", "null");

        c.manual("Reemplaza punteros por referencias o colecciones de Java");
        c.manual("Verifica la gestión de memoria y recursos");

        return c.ok(code);
    }
}
