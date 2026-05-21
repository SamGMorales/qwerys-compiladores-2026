package com.qwerys.qwerys_backend.adapter;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MongoDB access via the official synchronous Java driver.
 */
public final class MongoDBAdapter implements DatabaseAdapter {

    private static final String DB_TYPE = "mongodb";
    private static final Pattern DB_FIND =
            Pattern.compile("^\\s*db\\.([^.\\s]+)\\.find\\s*\\(", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public boolean testConnection(DatabaseConfig config) {
        try (MongoClient client = createClient(config)) {
            String dbName = databaseName(config);
            Document ping = new Document("ping", 1);
            client.getDatabase(dbName).runCommand(ping);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> getTableNames(DatabaseConfig config) {
        try (MongoClient client = createClient(config)) {
            List<String> out = new ArrayList<>();
            MongoDatabase db = client.getDatabase(databaseName(config));
            for (String name : db.listCollectionNames()) {
                out.add(name);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("MongoDB connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> getColumnNames(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        return new ArrayList<>(inferFieldTypes(config, tableName).keySet());
    }

    @Override
    public Map<String, String> getColumnTypes(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        return inferFieldTypes(config, tableName);
    }

    @Override
    public boolean tableExists(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        try (MongoClient client = createClient(config)) {
            MongoDatabase db = client.getDatabase(databaseName(config));
            for (String n : db.listCollectionNames()) {
                if (n.equals(tableName)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("MongoDB connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public QueryExecutionResult executeQuery(DatabaseConfig config, String query) {
        long start = System.currentTimeMillis();
        if (query == null) {
            return failure(start, "Query is null");
        }
        String raw = query.strip();
        if (raw.isEmpty()) {
            return failure(start, "Empty query");
        }
        Matcher m = DB_FIND.matcher(raw);
        if (!m.find()) {
            return failure(
                    start,
                    "Unsupported Mongo shell text. Expected pattern: db.<collection>.find({ ... })");
        }
        String collection = m.group(1);
        int argsStart = m.end() - 1;
        String remainder = raw.substring(argsStart);
        String filterJson = extractBalancedJson(remainder, '(', ')');
        if (filterJson == null) {
            return failure(start, "Could not parse find(...) filter");
        }
        try (MongoClient client = createClient(config)) {
            MongoDatabase db = client.getDatabase(databaseName(config));
            MongoCollection<Document> coll = db.getCollection(collection);
            Bson filter;
            String trimmed = filterJson.strip();
            if (trimmed.isEmpty()) {
                filter = new Document();
            } else {
                filter = Document.parse(trimmed);
            }
            FindIterable<Document> it = coll.find(filter);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Document doc : it) {
                rows.add(new LinkedHashMap<>(doc));
            }
            return success(start, rows, 0);
        } catch (Exception e) {
            return failure(start, e.getMessage());
        }
    }

    @Override
    public DatabaseSchema getSchema(DatabaseConfig config) {
        List<String> collections = getTableNames(config);
        DatabaseSchema schema = new DatabaseSchema();
        schema.setDbType(DB_TYPE);
        schema.setDatabaseName(databaseName(config));
        List<TableSchema> tables = new ArrayList<>();
        for (String coll : collections) {
            Map<String, String> types = getColumnTypes(config, coll);
            List<ColumnSchema> columns = new ArrayList<>();
            List<String> pk = new ArrayList<>();
            for (Map.Entry<String, String> e : types.entrySet()) {
                ColumnSchema cs = new ColumnSchema();
                cs.setColumnName(e.getKey());
                cs.setDataType(e.getValue());
                cs.setNullable(true);
                boolean id = "_id".equals(e.getKey());
                cs.setPrimaryKey(id);
                cs.setDefaultValue(null);
                columns.add(cs);
                if (id) {
                    pk.add("_id");
                }
            }
            TableSchema ts = new TableSchema();
            ts.setTableName(coll);
            ts.setColumns(columns);
            ts.setPrimaryKeys(pk);
            ts.setForeignKeys(List.of());
            tables.add(ts);
        }
        schema.setTables(tables);
        return schema;
    }

    private static Map<String, String> inferFieldTypes(DatabaseConfig config, String collectionName) {
        try (MongoClient client = createClient(config)) {
            MongoCollection<Document> coll =
                    client.getDatabase(databaseName(config)).getCollection(collectionName);
            Set<String> orderedKeys = new LinkedHashSet<>();
            Map<String, String> types = new LinkedHashMap<>();
            int n = 0;
            for (Document doc : coll.find().limit(10)) {
                n++;
                for (String key : doc.keySet()) {
                    if (!orderedKeys.contains(key)) {
                        orderedKeys.add(key);
                    }
                    String t = bsonTypeName(doc.get(key));
                    types.merge(key, t, MongoDBAdapter::mergeTypes);
                }
            }
            if (n == 0) {
                return Map.of();
            }
            Map<String, String> ordered = new LinkedHashMap<>();
            for (String k : orderedKeys) {
                ordered.put(k, types.getOrDefault(k, "unknown"));
            }
            return ordered;
        } catch (Exception e) {
            throw new IllegalStateException("MongoDB connection failed: " + e.getMessage(), e);
        }
    }

    private static String mergeTypes(String a, String b) {
        if (a.equals(b)) {
            return a;
        }
        return "mixed";
    }

    private static String bsonTypeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return "number";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Document) {
            return "object";
        }
        if (v instanceof List<?> || v.getClass().isArray()) {
            return "array";
        }
        String simple = v.getClass().getSimpleName();
        return simple.toLowerCase(Locale.ROOT);
    }

    private static String extractBalancedJson(String fromOpenParen, char open, char close) {
        int i = 0;
        while (i < fromOpenParen.length() && fromOpenParen.charAt(i) != open) {
            i++;
        }
        if (i >= fromOpenParen.length() || fromOpenParen.charAt(i) != open) {
            return null;
        }
        int depth = 0;
        boolean inSQuote = false;
        boolean inDQuote = false;
        int start = -1;
        for (; i < fromOpenParen.length(); i++) {
            char c = fromOpenParen.charAt(i);
            if (inSQuote) {
                if (c == '\'' && (i == 0 || fromOpenParen.charAt(i - 1) != '\\')) {
                    inSQuote = false;
                }
                continue;
            }
            if (inDQuote) {
                if (c == '"' && (i == 0 || fromOpenParen.charAt(i - 1) != '\\')) {
                    inDQuote = false;
                }
                continue;
            }
            if (c == '\'') {
                inSQuote = true;
                continue;
            }
            if (c == '"') {
                inDQuote = true;
                continue;
            }
            if (c == open) {
                if (depth == 0) {
                    start = i + 1;
                }
                depth++;
                continue;
            }
            if (c == close) {
                depth--;
                if (depth == 0 && start >= 0) {
                    return fromOpenParen.substring(start, i);
                }
            }
        }
        return null;
    }

    private static MongoClient createClient(DatabaseConfig config) {
        return MongoClients.create(connectionUri(config));
    }

    private static String connectionUri(DatabaseConfig config) {
        int timeoutMs = Math.max(1_000, config.connectionTimeoutSeconds() * 1000);
        String user = config.username();
        String pass = config.password();
        String host = config.host();
        int port = config.port();
        String db = databaseName(config);
        StringBuilder sb = new StringBuilder("mongodb://");
        if (user != null && !user.isBlank()) {
            sb.append(urlEncode(user))
                    .append(':')
                    .append(urlEncode(pass != null ? pass : ""))
                    .append('@');
        }
        sb.append(host).append(':').append(port).append('/');
        if (db != null && !db.isBlank()) {
            sb.append(db);
        }
        sb.append("?serverSelectionTimeoutMS=").append(timeoutMs).append("&connectTimeoutMS=").append(timeoutMs);
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String databaseName(DatabaseConfig config) {
        String db = config.database();
        if (db == null || db.isBlank()) {
            return "admin";
        }
        return db;
    }

    private static void requireTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is required");
        }
    }

    private static QueryExecutionResult success(
            long startMs, List<Map<String, Object>> rows, int affectedRows) {
        long elapsed = System.currentTimeMillis() - startMs;
        QueryExecutionResult r = new QueryExecutionResult();
        r.setSuccess(true);
        r.setRows(rows);
        r.setAffectedRows(affectedRows);
        r.setErrorMessage(null);
        r.setExecutionTimeMs(elapsed);
        return r;
    }

    private static QueryExecutionResult failure(long startMs, String message) {
        long elapsed = System.currentTimeMillis() - startMs;
        QueryExecutionResult r = new QueryExecutionResult();
        r.setSuccess(false);
        r.setRows(List.of());
        r.setAffectedRows(0);
        r.setErrorMessage(message);
        r.setExecutionTimeMs(elapsed);
        return r;
    }
}
