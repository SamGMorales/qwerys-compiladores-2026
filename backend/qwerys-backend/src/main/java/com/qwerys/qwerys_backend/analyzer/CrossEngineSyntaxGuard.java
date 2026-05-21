package com.qwerys.qwerys_backend.analyzer;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects when user input clearly belongs to a different database engine than the one selected
 * in the UI, so analyzers do not silently return "query is perfect".
 */
public final class CrossEngineSyntaxGuard {

    public enum TargetEngine {
        SQL,
        MONGODB,
        REDIS,
        CASSANDRA,
        ELASTICSEARCH,
        DYNAMODB_PARTIQL,
        DYNAMODB_EXPRESSION,
        DYNAMODB_JSON
    }

    private static final Pattern MONGO_SHELL = Pattern.compile("(?is)\\A\\s*db\\.");
    private static final Pattern RELATIONAL_SQL_HEAD = Pattern.compile(
            "(?is)^\\s*(SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|WITH|MERGE|CALL|EXEC|EXECUTE"
                    + "|SHOW|DESCRIBE|DESC|EXPLAIN|BEGIN|COMMIT|ROLLBACK|SAVEPOINT|USE|GRANT|REVOKE|LOCK|UNLOCK)\\b");
    private static final Pattern ES_REST_VERB = Pattern.compile(
            "(?is)^\\s*(GET|POST|PUT|DELETE|HEAD)\\s+[/\\w\\-*{}.]+");
    private static final Pattern DYNAMODB_SDK_EXPR_HEAD = Pattern.compile(
            "(?is)^\\s*(SET|REMOVE|ADD|DELETE)\\b");

    private CrossEngineSyntaxGuard() {
    }

    /**
     * @return a {@link SemanticError} with code {@code {PREFIX}-WRONG-ENGINE} when input clearly
     *         belongs to another engine family; empty when the check passes or input is blank.
     */
    public static Optional<SemanticError> check(String raw, TargetEngine selected, Locale ui) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String script = raw.strip();
        boolean mongo = containsMongoShellCall(script);
        boolean redis = containsRedisCommandLine(script);
        boolean sql = containsRelationalSql(script);
        boolean elasticsearch = looksLikeElasticsearchPayload(script);
        boolean dynamoJson = looksLikeDynamoDbApiJson(script);
        boolean dynamoSdk = looksLikeDynamoSdkExpression(script);

        return switch (selected) {
            case SQL -> {
                if (mongo || redis || (elasticsearch && !sql) || (dynamoJson && !sql)) {
                    yield Optional.of(wrongEngine("SQL", ui,
                            "This query does not look like SQL for the selected relational engine.",
                            "Este query no parece ser SQL para el motor relacional seleccionado.",
                            "Verify the database engine in the dropdown (MySQL, PostgreSQL, SQL Server, Oracle, SQLite) "
                                    + "or paste SQL syntax (SELECT, INSERT, UPDATE, …).",
                            "Verifica el motor en el selector (MySQL, PostgreSQL, SQL Server, Oracle, SQLite) "
                                    + "o usa sintaxis SQL (SELECT, INSERT, UPDATE, …)."));
                }
                yield Optional.empty();
            }
            case MONGODB -> {
                if (mongo) {
                    yield Optional.empty();
                }
                if (sql || redis || elasticsearch || dynamoJson || dynamoSdk) {
                    yield Optional.of(mongoWrong(ui));
                }
                yield Optional.of(mongoWrong(ui));
            }
            case REDIS -> {
                if (redis) {
                    yield Optional.empty();
                }
                if (mongo || sql || elasticsearch || dynamoJson) {
                    yield Optional.of(wrongEngine("RDS", ui,
                            "This query does not look like a Redis command.",
                            "Este query no parece ser un comando Redis.",
                            "Use Redis commands such as SET key value, GET key, HSET hash field value, or KEYS pattern.",
                            "Use comandos Redis como SET clave valor, GET clave, HSET hash campo valor o KEYS patrón."));
                }
                yield Optional.of(wrongEngine("RDS", ui,
                        "This query does not look like a Redis command.",
                        "Este query no parece ser un comando Redis.",
                        "Use Redis commands such as SET key value, GET key, HSET hash field value, or KEYS pattern.",
                        "Use comandos Redis como SET clave valor, GET clave, HSET hash campo valor o KEYS patrón."));
            }
            case CASSANDRA -> {
                if (mongo || redis || elasticsearch || dynamoJson) {
                    yield Optional.of(wrongEngine("CQL", ui,
                            "This query does not look like Cassandra CQL.",
                            "Este query no parece ser CQL de Cassandra.",
                            "Use CQL syntax: SELECT … FROM keyspace.table WHERE partition_key = ?",
                            "Use sintaxis CQL: SELECT … FROM keyspace.tabla WHERE partition_key = ?"));
                }
                yield Optional.empty();
            }
            case ELASTICSEARCH -> {
                if (elasticsearch) {
                    yield Optional.empty();
                }
                if (mongo || redis || sql) {
                    yield Optional.of(wrongEngine("ES", ui,
                            "This query does not look like Elasticsearch JSON or REST API syntax.",
                            "Este query no parece ser JSON ni sintaxis REST de Elasticsearch.",
                            "Use JSON Query DSL ({ \"query\": { … } }) or REST lines such as GET index/_search.",
                            "Use JSON Query DSL ({ \"query\": { … } }) o líneas REST como GET índice/_search."));
                }
                yield Optional.of(wrongEngine("ES", ui,
                        "This query does not look like Elasticsearch JSON or REST API syntax.",
                        "Este query no parece ser JSON ni sintaxis REST de Elasticsearch.",
                        "Use JSON Query DSL ({ \"query\": { … } }) or REST lines such as GET index/_search.",
                        "Use JSON Query DSL ({ \"query\": { … } }) o líneas REST como GET índice/_search."));
            }
            case DYNAMODB_PARTIQL -> {
                if (mongo || redis || (elasticsearch && !sql)) {
                    yield Optional.of(dynamoWrong(ui));
                }
                yield Optional.empty();
            }
            case DYNAMODB_EXPRESSION -> {
                if (looksLikeExpressionJsonEnvelope(script)) {
                    yield Optional.empty();
                }
                if (mongo || redis || sql || elasticsearch) {
                    yield Optional.of(dynamoWrong(ui));
                }
                if (!dynamoSdk) {
                    yield Optional.of(dynamoWrong(ui));
                }
                yield Optional.empty();
            }
            case DYNAMODB_JSON -> {
                if (dynamoJson) {
                    yield Optional.empty();
                }
                if (mongo || redis || sql) {
                    yield Optional.of(dynamoWrong(ui));
                }
                yield Optional.of(dynamoWrong(ui));
            }
        };
    }

    private static SemanticError mongoWrong(Locale ui) {
        return wrongEngine("MGO", ui,
                "This input does not look like MongoDB syntax. MongoDB queries must start with db.collectionName.method(...)",
                "Este input no parece ser sintaxis MongoDB. Las consultas MongoDB deben comenzar con db.nombreColeccion.método(...)",
                "Make sure you selected the correct database engine. MongoDB queries must start with db.collectionName.method(...), "
                        + "e.g. db.users.find({ field: 'value' })",
                "Verifica que elegiste el motor correcto. Las consultas MongoDB deben empezar con db.nombreColeccion.método(...), "
                        + "por ejemplo: db.usuarios.find({ campo: 'valor' })");
    }

    private static SemanticError dynamoWrong(Locale ui) {
        return wrongEngine("DDB", ui,
                "This payload does not look like DynamoDB PartiQL, SDK expressions, or management JSON.",
                "Esta carga no parece PartiQL, expresiones SDK ni JSON de gestión de DynamoDB.",
                "Use PartiQL (SELECT/INSERT/UPDATE/DELETE), SDK UpdateExpression strings, or JSON with TransactItems.",
                "Use PartiQL (SELECT/INSERT/UPDATE/DELETE), expresiones SDK UpdateExpression o JSON con TransactItems.");
    }

    private static SemanticError wrongEngine(
            String prefix,
            Locale ui,
            String messageEn,
            String messageEs,
            String suggestionEn,
            String suggestionEs) {
        return new SemanticError(
                prefix + "-WRONG-ENGINE",
                AnalysisMessages.t(ui, messageEn, messageEs),
                AnalysisMessages.t(ui, suggestionEn, suggestionEs),
                SemanticError.Severity.ERROR);
    }

    static boolean containsMongoShellCall(String script) {
        for (String segment : splitSegments(script)) {
            if (MONGO_SHELL.matcher(segment).lookingAt()) {
                return true;
            }
        }
        return false;
    }

    static boolean containsRelationalSql(String script) {
        for (String segment : splitSegments(script)) {
            String line = segment.strip();
            if (!line.isEmpty() && RELATIONAL_SQL_HEAD.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    static boolean containsRedisCommandLine(String script) {
        for (String segment : splitSegments(script)) {
            if (looksLikeRedisCommandLine(segment)) {
                return true;
            }
        }
        return false;
    }

    static boolean looksLikeRedisCommandLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String stripped = line.strip();
        String first = firstToken(stripped);
        if (first == null) {
            return false;
        }
        String cmd = first.toUpperCase(Locale.ROOT);
        if (RELATIONAL_SQL_HEAD.matcher(stripped).find()
                && !RedisLexer.KNOWN_COMMANDS.contains(cmd)) {
            return false;
        }
        if ("SELECT".equals(cmd) || "INSERT".equals(cmd) || "DELETE".equals(cmd)
                || "UPDATE".equals(cmd) || "CREATE".equals(cmd) || "DROP".equals(cmd)
                || "ALTER".equals(cmd) || "TRUNCATE".equals(cmd) || "WITH".equals(cmd)
                || "MERGE".equals(cmd)) {
            return false;
        }
        if ("SET".equals(cmd)) {
            String after = stripped.substring(3).stripLeading();
            if (after.contains("=") && !after.startsWith("\"")) {
                return false;
            }
        }
        return RedisLexer.KNOWN_COMMANDS.contains(cmd);
    }

    static boolean looksLikeElasticsearchPayload(String script) {
        String t = script.strip();
        if (t.isEmpty()) {
            return false;
        }
        if (ES_REST_VERB.matcher(t).find()) {
            return true;
        }
        int brace = t.indexOf('{');
        if (brace < 0) {
            return false;
        }
        String json = t.substring(brace).toLowerCase(Locale.ROOT);
        return json.contains("\"query\"")
                || json.contains("\"aggs\"")
                || json.contains("\"aggregations\"")
                || json.contains("\"processors\"")
                || json.contains("\"mappings\"")
                || json.contains("\"settings\"")
                || json.contains("\"source\"")
                || json.contains("\"script\"")
                || json.contains("\"template\"");
    }

    static boolean looksLikeDynamoDbApiJson(String script) {
        String t = script.strip();
        if (!t.startsWith("{")) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        return lower.contains("\"transactitems\"")
                || lower.contains("\"streamspecification\"")
                || lower.contains("\"eventsourcemapping\"")
                || lower.contains("\"pointintimerecoveryspecification\"")
                || lower.contains("\"dynamodbmanagement\"");
    }

    static boolean looksLikeDynamoSdkExpression(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String s = line.strip();
        String u = s.toUpperCase(Locale.ROOT);
        if (u.startsWith("SELECT") || u.startsWith("INSERT") || u.startsWith("DELETE FROM")) {
            return false;
        }
        if (s.indexOf('#') >= 0) {
            return true;
        }
        return DYNAMODB_SDK_EXPR_HEAD.matcher(s).find();
    }

    private static String[] splitSegments(String script) {
        return script.split("[;\r\n]+");
    }

    private static boolean looksLikeExpressionJsonEnvelope(String script) {
        String t = script.strip();
        return t.startsWith("{") && t.toLowerCase(Locale.ROOT).contains("\"expression\"");
    }

    private static String firstToken(String line) {
        String t = line.stripLeading();
        if (t.isEmpty()) {
            return null;
        }
        int sp = t.indexOf(' ');
        return (sp < 0 ? t : t.substring(0, sp)).toUpperCase(Locale.ROOT);
    }
}
