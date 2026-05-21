package com.qwerys.qwerys_backend.analyzer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DynamoDB analysis: PartiQL semantic checks on the SQL AST from {@link SqlLexer} / {@link SqlParser},
 * transactional JSON ({@code TransactItems}), management JSON for streams / backups / PITR / Lambda
 * ({@link #analyzeManagementPayload}), and Day-22 expression payloads via {@link DynamoDbExpressionAnalyzer}.
 *
 * <p>Without table key metadata, some rules use conservative heuristics (see ORDER BY and
 * partition-key inequality notes in method docs). For {@code DDB-API-001}, embed a size hint in the
 * raw query (e.g. block comment with the text {@code QWERYS_TABLE_SIZE_BYTES} then the byte count).
 */
public final class DynamoDbAnalyzer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Hint in PartiQL comments: {@code QWERYS_TABLE_SIZE_BYTES 2000000} — drives {@code DDB-API-001}. */
    private static final Pattern QWERYS_TABLE_SIZE_BYTES =
            Pattern.compile("(?is)QWERYS_TABLE_SIZE_BYTES\\s*[:=]?\\s*(\\d+)");

    private static final long ONE_MB = 1_048_576L;

    private static final Set<String> STREAM_VIEW_TYPES = Set.of(
            "KEYS_ONLY", "NEW_IMAGE", "OLD_IMAGE", "NEW_AND_OLD_IMAGES");

    private static final Pattern DYNAMO_TABLE_IN_ARN =
            Pattern.compile("(?i):table/([^/]+)/(?:backup|stream)/");

    private static final Pattern PLAINTEXT_RESTORE_FROM_BACKUP = Pattern.compile(
            "(?is)\\bRESTORE\\s+TABLE\\s+(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z0-9_.-]+))\\s+FROM\\s+BACKUP\\s+(arn:aws:dynamodb:[^\\s]+)\\b");

    private static final Pattern PLAIN_MGMT =
            Pattern.compile("(?is)\\b(CREATE\\s+BACKUP|RESTORE\\s+TABLE\\s+|(ENABLE|DISABLE)\\s+CONTINUOUS\\s+BACKUPS)\\b");

    private Locale ui = Locale.ENGLISH;

    private String t(String en, String es) {
        return AnalysisMessages.t(ui, en, es);
    }

    public List<DynamoDbSemanticError> analyze(AstNode ast, String rawSql) {
        return analyze(ast, rawSql, Locale.ENGLISH);
    }

    public List<DynamoDbSemanticError> analyze(AstNode ast, String rawSql, Locale uiLocale) {
        this.ui = uiLocale != null ? uiLocale : Locale.ENGLISH;
        List<DynamoDbSemanticError> out = new ArrayList<>();
        if (ast == null) {
            return out;
        }
        switch (ast.getNodeType()) {
            case "SELECT_STATEMENT" -> analyzeSelect(ast, rawSql, out);
            case "UPDATE_STATEMENT" -> analyzeUpdate(ast, out);
            case "DELETE_STATEMENT" -> analyzeDelete(ast, out);
            default -> {
            }
        }
        return out;
    }

    /**
     * Analyzes a low-level DynamoDB transactional request body (same shape as
     * {@code TransactWriteItems}/{@code TransactGetItems} API: root {@code TransactItems} array).
     *
     * <p>Suppress multi-table advisory {@code DDB-TX-004} by setting {@code "multiTableJustified": true}
     * on the JSON root when multiple tables are intentional.
     */
    public List<DynamoDbSemanticError> analyzeTransactRequest(String rawJson) {
        return analyzeTransactRequest(rawJson, Locale.ENGLISH);
    }

    public List<DynamoDbSemanticError> analyzeTransactRequest(String rawJson, Locale uiLocale) {
        this.ui = uiLocale != null ? uiLocale : Locale.ENGLISH;
        List<DynamoDbSemanticError> out = new ArrayList<>();
        if (rawJson == null || rawJson.isBlank()) {
            return out;
        }
        String s = rawJson.strip();
        if (!s.startsWith("{")) {
            return out;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(s);
            JsonNode items = root.get("TransactItems");
            if (items == null || !items.isArray()) {
                return out;
            }
            int n = items.size();
            if (n == 0) {
                return out;
            }
            if (n > 100) {
                out.add(new DynamoDbSemanticError(
                        "DDB-TX-001",
                        t("TransactItems exceeds DynamoDB limit of 100 actions per transaction",
                                "TransactItems supera el límite de DynamoDB de 100 acciones por transacción"),
                        t("Split the work into multiple transactions or batch unrelated writes differently.",
                                "Divida el trabajo en varias transacciones o agrupe las escrituras de otra forma."),
                        SemanticError.Severity.ERROR,
                        firstTableName(items),
                        "VERY_HIGH"));
            }
            boolean anyGet = false;
            boolean anyWrite = false;
            for (JsonNode it : items) {
                if (it == null || !it.isObject()) {
                    continue;
                }
                if (it.has("Get")) {
                    anyGet = true;
                }
                if (it.has("Put") || it.has("Update") || it.has("Delete") || it.has("ConditionCheck")) {
                    anyWrite = true;
                }
            }
            if (anyGet && anyWrite) {
                out.add(new DynamoDbSemanticError(
                        "DDB-TX-002",
                        t("TransactItems mixes read (Get) and write actions; TransactWriteItems cannot include Get",
                                "TransactItems mezcla lectura (Get) y escritura; TransactWriteItems no puede incluir Get"),
                        t("Use TransactGetItems for reads, or keep only Put/Update/Delete/ConditionCheck in this transaction.",
                                "Use TransactGetItems para reads, o deje solo Put/Update/Delete/ConditionCheck en esta transacción."),
                        SemanticError.Severity.ERROR,
                        firstTableName(items),
                        "VERY_HIGH"));
            }
            for (JsonNode it : items) {
                if (it == null || !it.isObject()) {
                    continue;
                }
                JsonNode cc = it.get("ConditionCheck");
                if (cc != null && cc.isObject()) {
                    String ce = textField(cc, "ConditionExpression");
                    if (ce.isEmpty()) {
                        out.add(new DynamoDbSemanticError(
                                "DDB-TX-003",
                                t("ConditionCheck requires a ConditionExpression in DynamoDB",
                                        "ConditionCheck requiere ConditionExpression en DynamoDB"),
                                t("Add a ConditionExpression that must hold for the check to succeed.",
                                        "Añada una ConditionExpression que deba cumplirse para que el check tenga éxito."),
                                SemanticError.Severity.ERROR,
                                textField(cc, "TableName"),
                                "HIGH"));
                    } else {
                        analyzeTransactItemCondition(textField(cc, "TableName"), ce, cc, out);
                    }
                }
                analyzeOpCondition(it.get("Put"), out);
                analyzeOpCondition(it.get("Update"), out);
                analyzeOpCondition(it.get("Delete"), out);
            }
            if (!root.path("multiTableJustified").asBoolean(false)) {
                Set<String> tables = distinctTableNames(items);
                if (tables.size() > 1) {
                    out.add(new DynamoDbSemanticError(
                            "DDB-TX-004",
                            t("Transaction spans multiple tables — confirm this is intentional",
                                    "La transacción abarca varias tablas — confirme que es intencional"),
                            t("Multi-table transactions are allowed but costlier and harder to reason about; document why, or set multiTableJustified true in the JSON root to silence this note.",
                                    "Las transacciones multi-tabla están permitidas pero son más costosas; documente el motivo, o ponga multiTableJustified true en la raíz del JSON para omitir este aviso."),
                            SemanticError.Severity.INFO,
                            String.join(", ", tables),
                            "MEDIUM"));
                }
            }
        } catch (JsonProcessingException e) {
            out.add(new DynamoDbSemanticError(
                    "DDB-TX-JSON",
                    t("Invalid JSON for DynamoDB transaction request", "JSON no válido para transacción DynamoDB"),
                    t("Provide a single JSON object with a TransactItems array.", "Proporcione un objeto JSON con TransactItems."),
                    SemanticError.Severity.ERROR,
                    "",
                    ""));
        }
        return out;
    }

    /**
     * True when {@code query} should be routed to {@link #analyzeManagementPayload(String, Locale)}
     * (streams, backups, PITR, Lambda on stream). Call only after {@code TransactItems} routing
     * has been ruled out.
     */
    public static boolean looksLikeManagementPayload(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String s = query.strip();
        if (PLAIN_MGMT.matcher(s).find()) {
            return true;
        }
        if (!s.startsWith("{")) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(s);
            return managementJsonHasRecognizedKeys(root);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private static boolean managementJsonHasRecognizedKeys(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (root.has("dynamoDbManagement")) {
            return true;
        }
        String[] keys = {
                "StreamSpecification",
                "streamSpecification",
                "EventSourceMapping",
                "eventSourceMapping",
                "EventSourceMappings",
                "eventSourceMappings",
                "RestoreTableFromBackupInput",
                "restoreTableFromBackup",
                "ContinuousBackupsDescription",
                "continuousBackupsDescription",
                "PointInTimeRecoverySpecification",
                "pointInTimeRecoverySpecification",
        };
        for (String k : keys) {
            if (root.has(k)) {
                return true;
            }
        }
        return root.has("environment") && root.has("ContinuousBackupsDescription")
                || root.has("environment") && root.has("continuousBackupsDescription");
    }

    /**
     * DynamoDB Streams, backup / continuous backups / PITR, and Lambda event source mappings
     * ({@code DDB-STR-*}, {@code DDB-BAK-*}). JSON uses AWS-like field names (or {@code dynamoDbManagement} wrapper).
     * Plaintext: {@code RESTORE TABLE … FROM BACKUP arn:…}, {@code CREATE BACKUP …},
     * {@code ENABLE|DISABLE CONTINUOUS BACKUPS …}.
     */
    public List<DynamoDbSemanticError> analyzeManagementPayload(String raw) {
        return analyzeManagementPayload(raw, Locale.ENGLISH);
    }

    public List<DynamoDbSemanticError> analyzeManagementPayload(String raw, Locale uiLocale) {
        this.ui = uiLocale != null ? uiLocale : Locale.ENGLISH;
        List<DynamoDbSemanticError> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String s = raw.strip();
        if (s.startsWith("{")) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(s);
                analyzeManagementJson(root, out);
            } catch (JsonProcessingException e) {
                out.add(new DynamoDbSemanticError(
                        "DDB-MGMT-JSON",
                        t("Invalid JSON for DynamoDB streams / backup payload",
                                "JSON no válido para streams / copias de seguridad DynamoDB"),
                        t("Send UpdateTable-style StreamSpecification, ContinuousBackupsDescription, restoreTableFromBackup, or EventSourceMapping JSON.",
                                "Envíe JSON estilo UpdateTable (StreamSpecification), ContinuousBackupsDescription, restoreTableFromBackup o EventSourceMapping."),
                        SemanticError.Severity.ERROR,
                        "",
                        ""));
            }
        } else {
            analyzeManagementPlaintext(s, out);
        }
        return out;
    }

    private void analyzeManagementJson(JsonNode root, List<DynamoDbSemanticError> out) {
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode ctx = root;
        if (root.has("dynamoDbManagement") && root.get("dynamoDbManagement").isObject()) {
            ctx = root.get("dynamoDbManagement");
        }

        JsonNode ss = fieldEither(ctx, root, "StreamSpecification");
        if (ss == null || ss.isNull()) {
            ss = fieldEither(ctx, root, "streamSpecification");
        }
        if (ss != null && ss.isObject() && ss.path("StreamEnabled").asBoolean(false)) {
            String vt = ss.path("StreamViewType").asText("").trim().toUpperCase(Locale.ROOT);
            if (!vt.isEmpty() && !STREAM_VIEW_TYPES.contains(vt)) {
                out.add(new DynamoDbSemanticError(
                        "DDB-STR-VIEW",
                        t("Unknown DynamoDB stream view type",
                                "Tipo de vista de stream DynamoDB desconocido"),
                        t("Use KEYS_ONLY, NEW_IMAGE, OLD_IMAGE, or NEW_AND_OLD_IMAGES.",
                                "Use KEYS_ONLY, NEW_IMAGE, OLD_IMAGE o NEW_AND_OLD_IMAGES."),
                        SemanticError.Severity.WARNING,
                        tableNameEither(ctx, root),
                        "LOW"));
            }
            JsonNode ttl = fieldEither(ctx, root, "TimeToLiveSpecification");
            if (ttl == null || ttl.isNull()) {
                ttl = fieldEither(ctx, root, "timeToLiveSpecification");
            }
            boolean ttlOn = ttl != null && ttl.isObject() && ttl.path("Enabled").asBoolean(false);
            if (!ttlOn) {
                out.add(new DynamoDbSemanticError(
                        "DDB-STR-001",
                        t("Stream enabled without TTL on the table — DynamoDB stream records are retained ~24h",
                                "Stream activo sin TTL en la tabla — los registros del stream se retienen ~24h"),
                        t("Streams retain 24h; configure TimeToLiveSpecification on the table if you need item expiry.",
                                "Los streams retienen 24h; configure TimeToLiveSpecification en la tabla si necesita caducidad de ítems."),
                        SemanticError.Severity.INFO,
                        tableNameEither(ctx, root),
                        "LOW"));
            }
        }

        JsonNode esm = fieldEither(ctx, root, "EventSourceMapping");
        if (esm != null && esm.isObject()) {
            analyzeLambdaStreamMapping(esm, out);
        }
        JsonNode esms = fieldEither(ctx, root, "EventSourceMappings");
        if (esms == null || esms.isNull()) {
            esms = fieldEither(ctx, root, "eventSourceMappings");
        }
        if (esms != null && esms.isArray()) {
            for (JsonNode n : esms) {
                if (n != null && n.isObject()) {
                    analyzeLambdaStreamMapping(n, out);
                }
            }
        }

        JsonNode cbd = fieldEither(ctx, root, "ContinuousBackupsDescription");
        if (cbd == null || cbd.isNull()) {
            cbd = fieldEither(ctx, root, "continuousBackupsDescription");
        }
        String env = textField(ctx, "environment");
        if (env.isEmpty()) {
            env = textField(root, "environment");
        }
        boolean production = "production".equalsIgnoreCase(env)
                || ctx.path("production").asBoolean(false)
                || root.path("production").asBoolean(false);
        boolean contOffForProd = false;
        if (cbd != null && cbd.isObject()) {
            String cbs = cbd.path("ContinuousBackupsStatus").asText("");
            JsonNode pitrDesc = cbd.get("PointInTimeRecoveryDescription");
            if (pitrDesc != null && pitrDesc.isObject()) {
                String ps = pitrDesc.path("PointInTimeRecoveryStatus").asText("");
                if ("DISABLED".equalsIgnoreCase(ps)) {
                    out.add(new DynamoDbSemanticError(
                            "DDB-BAK-003",
                            t("Point-in-time recovery (PITR) is disabled",
                                    "La recuperación punto en el tiempo (PITR) está desactivada"),
                            t("Consider enabling PITR for finer restore windows on top of backups.",
                                    "Considere activar PITR para ventanas de restauración más finas además de las copias."),
                            SemanticError.Severity.INFO,
                            tableNameEither(ctx, root),
                            "MEDIUM"));
                }
            }
            if (production && "DISABLED".equalsIgnoreCase(cbs)) {
                contOffForProd = true;
            }
        }
        if (production) {
            if (!ctx.path("continuousBackupsEnabled").asBoolean(true)
                    || !root.path("continuousBackupsEnabled").asBoolean(true)) {
                contOffForProd = true;
            }
        }
        if (production && contOffForProd) {
            out.add(new DynamoDbSemanticError(
                    "DDB-BAK-001",
                    t("Production table without continuous backups enabled",
                            "Tabla en producción sin copias de seguridad continuas activadas"),
                    t("Enable continuous backups for production tables to reduce data-loss risk.",
                            "Active continuous backups en tablas de producción para reducir riesgo de pérdida."),
                    SemanticError.Severity.WARNING,
                    tableNameEither(ctx, root),
                    "HIGH"));
        }

        JsonNode pitrSpec = fieldEither(ctx, root, "PointInTimeRecoverySpecification");
        if (pitrSpec == null || pitrSpec.isNull()) {
            pitrSpec = fieldEither(ctx, root, "pointInTimeRecoverySpecification");
        }
        if (pitrSpec != null && pitrSpec.isObject() && !pitrSpec.path("PointInTimeRecoveryEnabled").asBoolean(true)) {
            out.add(new DynamoDbSemanticError(
                    "DDB-BAK-003",
                    t("Point-in-time recovery (PITR) is disabled",
                            "La recuperación punto en el tiempo (PITR) está desactivada"),
                    t("Consider activating PITR.", "Considere activar PITR."),
                    SemanticError.Severity.INFO,
                    tableNameEither(ctx, root),
                    "MEDIUM"));
        }

        JsonNode rt = fieldEither(ctx, root, "restoreTableFromBackup");
        if (rt == null || rt.isNull()) {
            rt = fieldEither(ctx, root, "RestoreTableFromBackupInput");
        }
        if (rt != null && rt.isObject()) {
            String target = textField(rt, "TargetTableName");
            if (target.isEmpty()) {
                target = textField(rt, "targetTableName");
            }
            String arn = textField(rt, "BackupArn");
            if (arn.isEmpty()) {
                arn = textField(rt, "backupArn");
            }
            String sourceTable = extractTableFromDynamoArn(arn);
            if (!target.isEmpty() && sourceTable != null && target.equalsIgnoreCase(sourceTable)) {
                out.add(new DynamoDbSemanticError(
                        "DDB-BAK-002",
                        t("Restoring a backup to a table with the same name as the source can overwrite live data",
                                "Restaurar un backup a una tabla con el mismo nombre que el origen puede sobrescribir datos en uso"),
                        t("Use a new TargetTableName, then swap or migrate carefully.",
                                "Use un TargetTableName nuevo y luego intercambie o migre con cuidado."),
                        SemanticError.Severity.ERROR,
                        target,
                        "VERY_HIGH"));
            }
        }
    }

    private void analyzeLambdaStreamMapping(JsonNode esm, List<DynamoDbSemanticError> out) {
        String arn = textField(esm, "EventSourceArn");
        if (arn.isEmpty() || !arn.contains("/stream/")) {
            return;
        }
        boolean hasDlq = esm.has("DestinationConfig")
                && esm.get("DestinationConfig").isObject()
                && esm.get("DestinationConfig").has("OnFailure");
        if (!hasDlq && esm.has("DeadLetterConfig")) {
            JsonNode dlc = esm.get("DeadLetterConfig");
            hasDlq = dlc != null && dlc.isObject() && !textField(dlc, "TargetArn").isEmpty();
        }
        if (!hasDlq) {
            out.add(new DynamoDbSemanticError(
                    "DDB-STR-002",
                    t("Lambda trigger on a DynamoDB stream without a dead-letter queue (DLQ)",
                            "Trigger Lambda sobre stream DynamoDB sin cola de mensajes fallidos (DLQ)"),
                    t("Consider a Dead Letter Queue (OnFailure destination or DeadLetterConfig) for poison or failing records.",
                            "Considere una Dead Letter Queue (destino OnFailure o DeadLetterConfig) para registros erróneos."),
                    SemanticError.Severity.WARNING,
                    textField(esm, "FunctionName"),
                    "MEDIUM"));
        }
    }

    private void analyzeManagementPlaintext(String s, List<DynamoDbSemanticError> out) {
        Matcher m = PLAINTEXT_RESTORE_FROM_BACKUP.matcher(s);
        if (!m.find()) {
            return;
        }
        String g1 = m.group(1);
        String g2 = m.group(2);
        String g3 = m.group(3);
        String tbl = g1 != null && !g1.isEmpty() ? g1 : (g2 != null && !g2.isEmpty() ? g2 : g3);
        String arn = m.group(4);
        String sourceTable = extractTableFromDynamoArn(arn);
        if (tbl != null && sourceTable != null && tbl.equalsIgnoreCase(sourceTable)) {
            out.add(new DynamoDbSemanticError(
                    "DDB-BAK-002",
                    t("Restoring a backup to a table with the same name as the source can overwrite live data",
                            "Restaurar un backup a una tabla con el mismo nombre que el origen puede sobrescribir datos en uso"),
                    t("Use a new table name, then swap or migrate carefully.",
                            "Use un nombre de tabla nuevo y luego intercambie o migre con cuidado."),
                    SemanticError.Severity.ERROR,
                    tbl,
                    "VERY_HIGH"));
        }
    }

    private static JsonNode fieldEither(JsonNode ctx, JsonNode root, String field) {
        if (ctx != null && ctx.has(field)) {
            JsonNode v = ctx.get(field);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        if (root != null && root.has(field)) {
            JsonNode v = root.get(field);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static String tableNameEither(JsonNode ctx, JsonNode root) {
        String t = textField(ctx, "TableName");
        if (!t.isEmpty()) {
            return t;
        }
        return textField(root, "TableName");
    }

    /** Table segment from a DynamoDB table, backup, or stream ARN. */
    static String extractTableFromDynamoArn(String arn) {
        if (arn == null || arn.isBlank()) {
            return null;
        }
        Matcher m = DYNAMO_TABLE_IN_ARN.matcher(arn);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private void analyzeOpCondition(JsonNode op, List<DynamoDbSemanticError> out) {
        if (op == null || !op.isObject()) {
            return;
        }
        String ce = textField(op, "ConditionExpression");
        if (ce.isEmpty()) {
            return;
        }
        analyzeTransactItemCondition(textField(op, "TableName"), ce, op, out);
    }

    private void analyzeTransactItemCondition(
            String tableName,
            String conditionExpr,
            JsonNode operationNode,
            List<DynamoDbSemanticError> out) {
        if (conditionExpr == null || conditionExpr.isBlank()) {
            return;
        }
        try {
            ObjectNode wrap = OBJECT_MAPPER.createObjectNode();
            wrap.put("kind", "CONDITION");
            wrap.put("expression", conditionExpr);
            JsonNode names = operationNode.get("ExpressionAttributeNames");
            if (names != null && names.isObject()) {
                wrap.set("expressionAttributeNames", names);
            }
            JsonNode values = operationNode.get("ExpressionAttributeValues");
            if (values != null && values.isObject()) {
                wrap.set("expressionAttributeValues", values);
            }
            Map<String, String> inferred = typesFromDynamoTypedAttributes(operationNode.get("Key"));
            inferred.putAll(typesFromDynamoTypedAttributes(operationNode.get("Item")));
            if (!inferred.isEmpty()) {
                ObjectNode typesNode = OBJECT_MAPPER.createObjectNode();
                for (Map.Entry<String, String> e : inferred.entrySet()) {
                    typesNode.put(e.getKey(), e.getValue());
                }
                wrap.set("attributeTypes", typesNode);
            }
            DynamoDbExpressionPayload payload =
                    DynamoDbExpressionPayload.parse(OBJECT_MAPPER.writeValueAsString(wrap));
            DynamoDbExpressionAnalyzer dex = new DynamoDbExpressionAnalyzer();
            List<SemanticError> inner = dex.analyze(payload, this.ui);
            for (SemanticError se : inner) {
                out.add(fromSemanticError(se, tableName));
            }
        } catch (IllegalArgumentException | JsonProcessingException ignored) {
            // Do not fail the whole transaction analysis on one bad expression fragment
        }
    }

    private DynamoDbSemanticError fromSemanticError(SemanticError se, String tableName) {
        String tbl = tableName != null ? tableName : "";
        return new DynamoDbSemanticError(
                se.code(),
                se.message(),
                se.suggestion(),
                se.severity(),
                tbl,
                "");
    }

    private static Map<String, String> typesFromDynamoTypedAttributes(JsonNode doc) {
        Map<String, String> m = new HashMap<>();
        if (doc == null || !doc.isObject()) {
            return m;
        }
        Iterator<String> it = doc.fieldNames();
        while (it.hasNext()) {
            String attr = it.next();
            JsonNode typed = doc.get(attr);
            if (typed != null && typed.isObject()) {
                Iterator<String> tIt = typed.fieldNames();
                if (tIt.hasNext()) {
                    m.put(attr, tIt.next().toUpperCase(Locale.ROOT));
                }
            }
        }
        return m;
    }

    private static String textField(JsonNode obj, String field) {
        if (obj == null) {
            return "";
        }
        JsonNode n = obj.get(field);
        if (n == null || !n.isTextual()) {
            return "";
        }
        String t = n.asText();
        return t != null ? t.trim() : "";
    }

    private static String firstTableName(JsonNode items) {
        if (items == null || !items.isArray()) {
            return "";
        }
        for (JsonNode it : items) {
            if (it == null || !it.isObject()) {
                continue;
            }
            String[] keys = {"Put", "Update", "Delete", "ConditionCheck", "Get"};
            for (String k : keys) {
                if (it.has(k)) {
                    String tn = textField(it.get(k), "TableName");
                    if (!tn.isEmpty()) {
                        return tn;
                    }
                }
            }
        }
        return "";
    }

    private static Set<String> distinctTableNames(JsonNode items) {
        Set<String> set = new LinkedHashSet<>();
        if (items == null || !items.isArray()) {
            return set;
        }
        for (JsonNode it : items) {
            if (it == null || !it.isObject()) {
                continue;
            }
            Iterator<String> fn = it.fieldNames();
            while (fn.hasNext()) {
                String opKey = fn.next();
                JsonNode op = it.get(opKey);
                if (op != null && op.isObject()) {
                    String tn = textField(op, "TableName");
                    if (!tn.isEmpty()) {
                        set.add(tn);
                    }
                }
            }
        }
        return set;
    }

    private void analyzeSelect(AstNode select, String rawSql, List<DynamoDbSemanticError> out) {
        String table = primaryTableName(select);

        if (containsJoin(select)) {
            out.add(new DynamoDbSemanticError(
                    "DDB-JOIN",
                    t("DynamoDB PartiQL does not support JOINs",
                            "PartiQL de DynamoDB no admite JOIN"),
                    t("Use a single table per statement, query related items with separate requests or design PK/SK patterns.",
                            "Use una sola tabla por sentencia, consulte ítems relacionados con peticiones separadas o diseñe PK/SK."),
                    SemanticError.Severity.ERROR,
                    table,
                    "VERY_HIGH"));
        }
        if (findChild(select, "GROUP_BY") != null) {
            out.add(new DynamoDbSemanticError(
                    "DDB-GROUP",
                    t("DynamoDB PartiQL does not support GROUP BY",
                            "PartiQL de DynamoDB no admite GROUP BY"),
                    t("Pre-aggregate in application code, use a GSI with precomputed aggregates, or use another tool.",
                            "Pre-agregue en la aplicación, use un GSI con agregados precomputados u otra herramienta."),
                    SemanticError.Severity.ERROR,
                    table,
                    "VERY_HIGH"));
        }
        if (findChild(select, "HAVING_CLAUSE") != null) {
            out.add(new DynamoDbSemanticError(
                    "DDB-HAVING",
                    t("DynamoDB PartiQL does not support HAVING",
                            "PartiQL de DynamoDB no admite HAVING"),
                    t("Filter before aggregation outside DynamoDB, or redesign the access pattern.",
                            "Filtre antes de agregar fuera de DynamoDB o rediseñe el patrón de acceso."),
                    SemanticError.Severity.ERROR,
                    table,
                    "VERY_HIGH"));
        }

        AstNode where = findChild(select, "WHERE_CLAUSE");
        if (where == null) {
            out.add(new DynamoDbSemanticError(
                    "DDB-WHERE-SELECT",
                    t("Full table scan in DynamoDB is expensive. Include Partition Key in WHERE",
                            "El escaneo completo en DynamoDB es costoso. Incluya la clave de partición en WHERE"),
                    t("Add an equality (or key-condition) on the partition key to scope the read.",
                            "Añada igualdad (o condición de clave) sobre la clave de partición para acotar la lectura."),
                    SemanticError.Severity.WARNING,
                    table,
                    "VERY_HIGH"));
        }

        if (isSelectStar(select)) {
            if (tableSizeBytesHintExceedsOneMb(rawSql)) {
                out.add(new DynamoDbSemanticError(
                        "DDB-API-001",
                        t("PartiQL SELECT * on a large table — paginate manually",
                                "PartiQL SELECT * sobre tabla grande — pagine manualmente"),
                        t("Paginate manually using LastEvaluatedKey / pagination tokens.",
                                "Pagina manualmente usando LastEvaluatedKey / tokens de paginación."),
                        SemanticError.Severity.WARNING,
                        table,
                        "HIGH"));
            } else {
                out.add(new DynamoDbSemanticError(
                        "DDB-SELECT-STAR",
                        t("Avoid SELECT * in DynamoDB — fetch only the attributes you need to reduce RCU cost",
                                "Evite SELECT * en DynamoDB — traiga solo los atributos necesarios para reducir RCU"),
                        t("List projection expressions or explicit columns so unused attributes are not read.",
                                "Liste proyecciones o columnas explícitas para no leer atributos no usados."),
                        SemanticError.Severity.INFO,
                        table,
                        "HIGH"));
            }
        }

        if (where != null) {
            AstNode cond = firstChild(where);
            walkWhere(cond, table, out);
        }

        if (findChild(select, "ORDER_BY") != null) {
            out.add(new DynamoDbSemanticError(
                    "DDB-ORDER",
                    t("ORDER BY in DynamoDB only works on Sort Key within a partition",
                            "ORDER BY en DynamoDB solo aplica a la clave de ordenación dentro de una partición"),
                    t("Ensure ORDER BY uses the table sort key (within a single partition key) or remove it to avoid unexpected scans.",
                            "Asegúrese de que ORDER BY use la sort key (dentro de una misma partición) o elimínelo para evitar escaneos inesperados."),
                    SemanticError.Severity.WARNING,
                    table,
                    "MEDIUM"));
        }
    }

    private void analyzeUpdate(AstNode update, List<DynamoDbSemanticError> out) {
        String table = primaryTableNameFromStatement(update);
        AstNode where = findChild(update, "WHERE_CLAUSE");
        if (where == null) {
            out.add(new DynamoDbSemanticError(
                    "DDB-API-002",
                    t("UPDATE PartiQL requires the partition key in WHERE",
                            "UPDATE PartiQL requiere la clave de partición en WHERE"),
                    t("Requerido: add WHERE with partition key equality (and sort key if the table defines one).",
                            "Requerido: añada WHERE con igualdad sobre la clave de partición (y sort key si la tabla la define)."),
                    SemanticError.Severity.ERROR,
                    table,
                    "VERY_HIGH"));
        } else {
            if (!whereHasEqualityComparison(where)) {
                out.add(new DynamoDbSemanticError(
                        "DDB-API-002",
                        t("UPDATE PartiQL requires the partition key in WHERE",
                                "UPDATE PartiQL requiere la clave de partición en WHERE"),
                        t("Requerido: include at least one equality (=) condition so DynamoDB can target a single item or key set.",
                                "Requerido: incluya al menos una igualdad (=) para que DynamoDB pueda acotar el ítem o conjunto de claves."),
                        SemanticError.Severity.ERROR,
                        table,
                        "VERY_HIGH"));
            }
            walkWhere(firstChild(where), table, out);
        }
    }

    private void analyzeDelete(AstNode delete, List<DynamoDbSemanticError> out) {
        String table = primaryTableNameFromStatement(delete);
        if (findChild(delete, "WHERE_CLAUSE") == null) {
            out.add(new DynamoDbSemanticError(
                    "DDB-DELETE-NO-WHERE",
                    t("DELETE without WHERE in DynamoDB requires Partition Key",
                            "DELETE sin WHERE en DynamoDB requiere la clave de partición"),
                    t("Add a WHERE clause that specifies the partition key (and sort key when required).",
                            "Añada WHERE con la clave de partición (y sort key cuando sea obligatoria)."),
                    SemanticError.Severity.ERROR,
                    table,
                    "VERY_HIGH"));
        } else {
            AstNode where = findChild(delete, "WHERE_CLAUSE");
            walkWhere(firstChild(where), table, out);
        }
    }

    private void walkWhere(AstNode n, String table, List<DynamoDbSemanticError> out) {
        if (n == null) {
            return;
        }
        switch (n.getNodeType()) {
            case "AND_EXPR", "OR_EXPR" -> {
                if (n.getChildren().size() >= 2) {
                    walkWhere(n.getChildren().get(0), table, out);
                    walkWhere(n.getChildren().get(1), table, out);
                }
            }
            case "NOT_EXPR" -> {
                if (!n.getChildren().isEmpty()) {
                    walkWhere(n.getChildren().get(0), table, out);
                }
            }
            case "LIKE_EXPR", "NOT_LIKE_EXPR" -> checkLeadingWildcard(n, table, out);
            case "NOT_IN_EXPR" -> {
                if (!n.getChildren().isEmpty()
                        && "COLUMN_REF".equals(n.getChildren().get(0).getNodeType())) {
                    out.add(new DynamoDbSemanticError(
                            "DDB-PK-NEG",
                            t("DynamoDB does not support not-equal conditions on Partition Key",
                                    "DynamoDB no admite condiciones de desigualdad en la clave de partición"),
                            t("NOT IN on a key attribute is unsupported for the partition key; use supported key operators or filter only on non-key attributes.",
                                    "NOT IN sobre un atributo de clave no es válido para la partición; use operadores admitidos o filtre solo atributos no clave."),
                            SemanticError.Severity.WARNING,
                            table,
                            "MEDIUM"));
                }
                for (AstNode c : n.getChildren()) {
                    walkWhere(c, table, out);
                }
            }
            case "COMPARISON" -> {
                String op = n.getValue();
                if (op != null) {
                    String o = op.trim();
                    if ("!=".equals(o) || "<>".equals(o)) {
                        AstNode left = !n.getChildren().isEmpty() ? n.getChildren().get(0) : null;
                        if (left != null && "COLUMN_REF".equals(left.getNodeType())) {
                            out.add(new DynamoDbSemanticError(
                                    "DDB-PK-NEG",
                                    t("DynamoDB does not support not-equal conditions on Partition Key",
                                            "DynamoDB no admite condiciones de desigualdad en la clave de partición"),
                                    t("If this column is the partition key, use equality or supported key-condition shapes; otherwise confirm it is not the partition key.",
                                            "Si esta columna es la clave de partición, use igualdad o formas admitidas; si no, confirme que no es la partición."),
                                    SemanticError.Severity.WARNING,
                                    table,
                                    "MEDIUM"));
                        }
                    }
                }
                for (AstNode c : n.getChildren()) {
                    walkWhere(c, table, out);
                }
            }
            default -> {
                for (AstNode c : n.getChildren()) {
                    walkWhere(c, table, out);
                }
            }
        }
    }

    private void checkLeadingWildcard(AstNode likeNode, String table, List<DynamoDbSemanticError> out) {
        if (likeNode.getChildren().size() < 2) {
            return;
        }
        AstNode patternNode = likeNode.getChildren().get(1);
        if (!"LITERAL".equals(patternNode.getNodeType())) {
            return;
        }
        String raw = patternNode.getValue();
        String unquoted = unquoteStringLiteral(raw);
        if (unquoted.startsWith("%")) {
            out.add(new DynamoDbSemanticError(
                    "DDB-LIKE-LEAD",
                    t("Leading wildcard causes full scan in DynamoDB",
                            "El comodín inicial provoca escaneo completo en DynamoDB"),
                    t("Avoid a leading '%' in LIKE; anchor the pattern or use begins_with / prefix queries.",
                            "Evite '%' inicial en LIKE; ancle el patrón o use begins_with / consultas por prefijo."),
                    SemanticError.Severity.WARNING,
                    table,
                    "HIGH"));
        }
    }

    private static String unquoteStringLiteral(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static boolean isSelectStar(AstNode select) {
        AstNode colList = findChild(select, "COLUMN_LIST");
        if (colList == null || colList.getChildren().size() != 1) {
            return false;
        }
        AstNode only = colList.getChildren().get(0);
        return "COLUMN_REF".equals(only.getNodeType()) && "*".equals(only.getValue());
    }

    private static String primaryTableName(AstNode select) {
        AstNode fromRoot = tableExpressionRoot(select);
        if (fromRoot != null) {
            AstNode tr = firstDescendantOfType(fromRoot, "TABLE_REF");
            if (tr != null && tr.getValue() != null) {
                return tr.getValue();
            }
        }
        return "";
    }

    private static AstNode tableExpressionRoot(AstNode select) {
        List<AstNode> ch = select.getChildren();
        int i = 0;
        if (i < ch.size() && "DISTINCT".equals(ch.get(i).getNodeType())) {
            i++;
        }
        if (i < ch.size() && "COLUMN_LIST".equals(ch.get(i).getNodeType())) {
            i++;
        }
        if (i < ch.size()) {
            return ch.get(i);
        }
        return null;
    }

    private static String primaryTableNameFromStatement(AstNode stmt) {
        AstNode tr = findChild(stmt, "TABLE_REF");
        if (tr != null && tr.getValue() != null) {
            return tr.getValue();
        }
        return "";
    }

    private static boolean containsJoin(AstNode select) {
        AstNode fromRoot = tableExpressionRoot(select);
        return fromRoot != null && firstDescendantMatching(fromRoot, DynamoDbAnalyzer::isJoinNode) != null;
    }

    private static boolean isJoinNode(AstNode n) {
        if (n == null) {
            return false;
        }
        String t = n.getNodeType();
        return "JOIN_EXPRESSION".equals(t) || "JOIN".equals(t) || "LATERAL_JOIN".equals(t);
    }

    private static AstNode firstDescendantMatching(AstNode root, java.util.function.Predicate<AstNode> pred) {
        if (root == null) {
            return null;
        }
        if (pred.test(root)) {
            return root;
        }
        for (AstNode c : root.getChildren()) {
            AstNode hit = firstDescendantMatching(c, pred);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static AstNode firstDescendantOfType(AstNode root, String type) {
        return firstDescendantMatching(root, n -> type.equals(n.getNodeType()));
    }

    private static AstNode findChild(AstNode parent, String nodeType) {
        for (AstNode c : parent.getChildren()) {
            if (nodeType.equals(c.getNodeType())) {
                return c;
            }
        }
        return null;
    }

    private static boolean tableSizeBytesHintExceedsOneMb(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return false;
        }
        Matcher m = QWERYS_TABLE_SIZE_BYTES.matcher(rawSql);
        long max = 0;
        while (m.find()) {
            try {
                max = Math.max(max, Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return max > ONE_MB;
    }

    private static boolean whereHasEqualityComparison(AstNode whereClause) {
        if (whereClause == null) {
            return false;
        }
        AstNode cond = firstChild(whereClause);
        return subtreeHasEqualityComparison(cond);
    }

    private static boolean subtreeHasEqualityComparison(AstNode n) {
        if (n == null) {
            return false;
        }
        if ("COMPARISON".equals(n.getNodeType())) {
            String op = n.getValue();
            if (op != null && "=".equals(op.trim())) {
                return true;
            }
        }
        for (AstNode c : n.getChildren()) {
            if (subtreeHasEqualityComparison(c)) {
                return true;
            }
        }
        return false;
    }

    private static AstNode firstChild(AstNode parent) {
        List<AstNode> ch = parent.getChildren();
        return ch.isEmpty() ? null : ch.get(0);
    }
}
