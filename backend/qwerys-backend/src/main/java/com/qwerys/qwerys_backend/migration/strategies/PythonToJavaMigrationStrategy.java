package com.qwerys.qwerys_backend.migration.strategies;

import com.qwerys.qwerys_backend.migration.MigrationResult;
import com.qwerys.qwerys_backend.migration.MigrationStrategy;
import org.springframework.stereotype.Component;

import static com.qwerys.qwerys_backend.migration.strategies.MigrationStrategySupport.*;

@Component
public class PythonToJavaMigrationStrategy implements MigrationStrategy {

    @Override
    public String getSourceLanguage() {
        return "PYTHON";
    }

    @Override
    public String getTargetLanguage() {
        return "JAVA";
    }

    @Override
    public MigrationResult migrate(String sourceCode) {
        Context c = ctx();
        String code = sourceCode == null ? "" : sourceCode;

        code = replaceAll(code, "(?m)^\\s*print\\s*\\(", "System.out.println(");
        code = replaceAll(code, "\\bNone\\b", "null");
        code = replaceAll(code, "\\bTrue\\b", "true");
        code = replaceAll(code, "\\bFalse\\b", "false");
        code = replaceAll(code, "(?i)\\blen\\s*\\(([^)]+)\\)", "$1.length()");
        c.warn("Verifica si .length() aplica a String o a una colección");
        code = replaceAll(code, "(?i)\\brange\\s*\\(([^)]*)\\)", "/* range($1) */ 0");
        code = replaceAll(code, "(?m)(\\w+)\\s*:\\s*$", "$1 {");

        c.manual("Añade tipos explícitos a todas las variables y parámetros");
        c.manual("Convierte las funciones def en métodos con tipo de retorno");
        c.manual("Revisa la indentación Python: convierte bloques a llaves {} manualmente si hace falta");

        return c.ok(code);
    }
}
