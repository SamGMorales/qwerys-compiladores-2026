package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationStrategy;
import org.springframework.stereotype.Component;

import static com.qwerys.qwerys_backend.migration.strategies.MigrationStrategySupport.*;

@Component
public class TypeScriptToJavaMigrationStrategy implements MigrationStrategy {

    @Override
    public String getSourceLanguage() {
        return "TYPESCRIPT";
    }

    @Override
    public String getTargetLanguage() {
        return "JAVA";
    }

    @Override
    public MigrationResult migrate(String sourceCode) {
        Context c = ctx();
        String code = sourceCode == null ? "" : sourceCode;

        code = replaceAll(code, "console\\.log\\s*\\(", "System.out.println(");
        code = replaceAll(code, "\\bstring\\b", "String");
        code = replaceAll(code, "\\bnumber\\b", "double");
        code = replaceAll(code, "\\bboolean\\b", "boolean");
        code = replaceAll(code, "(?i)\\bconst\\s+|\\blet\\s+", "");
        code = replaceAll(code, ":\\s*\\w+(?:<[^>]+>)?", "");
        code = replaceAll(code, "(?m)^\\s*export\\s+class\\s+", "public class ");
        code = replaceAll(code, "\\bundefined\\b", "null");
        if (code.contains("async") || code.contains("await")) {
            c.warn("Convierte async/await a CompletableFuture<T> o callbacks");
        }

        c.manual("Añade tipos explícitos donde TypeScript usaba inferencia");
        c.manual("Revisa el manejo de promesas y código asíncrono");

        return c.ok(code);
    }
}
