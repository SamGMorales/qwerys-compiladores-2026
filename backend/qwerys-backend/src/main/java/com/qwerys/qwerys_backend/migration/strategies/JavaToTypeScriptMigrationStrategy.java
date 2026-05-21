package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationStrategy;
import org.springframework.stereotype.Component;

import static com.qwerys.qwerys_backend.migration.strategies.MigrationStrategySupport.*;

@Component
public class JavaToTypeScriptMigrationStrategy implements MigrationStrategy {

    @Override
    public String getSourceLanguage() {
        return "JAVA";
    }

    @Override
    public String getTargetLanguage() {
        return "TYPESCRIPT";
    }

    @Override
    public MigrationResult migrate(String sourceCode) {
        Context c = ctx();
        String code = sourceCode == null ? "" : sourceCode;

        code = replaceAll(code, "System\\.out\\.println\\s*\\(", "console.log(");
        code = replaceAll(code, "\\bString\\b", "string");
        code = replaceAll(code, "\\b(?:int|long|float|double)\\b", "number");
        code = replaceAll(code, "\\bboolean\\b", "boolean");
        code = replaceAll(code, "(?i)ArrayList<([^>]+)>", "Array<$1>");
        code = replaceAll(code, "(?i)HashMap<([^,>]+)\\s*,\\s*([^>]+)>", "Map<$1, $2>");
        code = replaceAll(code, "(?m)^\\s*public\\s+class\\s+", "export class ");
        code = replaceAll(
                code,
                "import\\s+([\\w.]+)\\.([\\w]+)\\s*;",
                "import { $2 } from './$2';");

        c.manual("Revisa los tipos genéricos — TypeScript tiene inferencia de tipos");
        c.manual("Agrega interfaces TypeScript donde corresponda");

        return c.ok(code);
    }
}
