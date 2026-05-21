package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationStrategy;
import org.springframework.stereotype.Component;

import static com.qwerys.qwerys_backend.migration.strategies.MigrationStrategySupport.*;

@Component
public class JavaToPythonMigrationStrategy implements MigrationStrategy {

    @Override
    public String getSourceLanguage() {
        return "JAVA";
    }

    @Override
    public String getTargetLanguage() {
        return "PYTHON";
    }

    @Override
    public MigrationResult migrate(String sourceCode) {
        Context c = ctx();
        String code = sourceCode == null ? "" : sourceCode;

        code = replaceAll(code, "System\\.out\\.println\\s*\\(", "print(");
        code = replaceAll(code, "\\bnull\\b", "None");
        code = replaceAll(code, "\\btrue\\b", "True");
        code = replaceAll(code, "\\bfalse\\b", "False");
        code = replaceAll(code, "\\.length\\s*\\(\\s*\\)", "");
        c.warn("Verifica el contexto — puede ser len() o .size()");
        code = replaceAll(code, "(?i)ArrayList<[^>]+>", "list");
        code = replaceAll(code, "(?i)HashMap<[^>]+>", "dict");
        code = replaceAll(code, "(?m)^\\s*(?:public|private|protected)?\\s*(?:static\\s+)?(?:final\\s+)?\\w+(?:<[^>]+>)?\\s+(\\w+)\\s*=", "$1 =");
        code = replaceAll(code, "[{}]", "");

        c.manual("Convierte los bloques {} a indentación de 4 espacios");
        c.manual("Revisa el manejo de excepciones (try/catch → try/except)");

        return c.ok(code);
    }
}
