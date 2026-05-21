package com.qwerys.qwerys_backend.adapter;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 8.x via the official Java API client.
 */
public final class ElasticsearchAdapter implements DatabaseAdapter {

    private static final String DB_TYPE = "elasticsearch";

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public boolean testConnection(DatabaseConfig config) {
        try (RestClient rc = buildRestClient(config)) {
            try (ElasticsearchTransport tr = new RestClientTransport(rc, new JacksonJsonpMapper())) {
                ElasticsearchClient client = new ElasticsearchClient(tr);
                HealthResponse hr = client.cluster().health();
                return hr.status() != HealthStatus.Red;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> getTableNames(DatabaseConfig config) {
        try (RestClient rc = buildRestClient(config)) {
            try (ElasticsearchTransport tr = new RestClientTransport(rc, new JacksonJsonpMapper())) {
                ElasticsearchClient client = new ElasticsearchClient(tr);
                GetIndexResponse resp = client.indices().get(g -> g.index("*"));
                return new ArrayList<>(resp.result().keySet());
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<String> getColumnNames(DatabaseConfig config, String tableName) {
        requireIndexName(tableName);
        Map<String, String> types = getColumnTypes(config, tableName);
        return new ArrayList<>(types.keySet());
    }

    @Override
    public Map<String, String> getColumnTypes(DatabaseConfig config, String tableName) {
        requireIndexName(tableName);
        try (RestClient rc = buildRestClient(config)) {
            try (ElasticsearchTransport tr = new RestClientTransport(rc, new JacksonJsonpMapper())) {
                ElasticsearchClient client = new ElasticsearchClient(tr);
                GetMappingResponse mr = client.indices().getMapping(m -> m.index(tableName));
                return flattenMappingFields(mr, tableName);
            }
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Override
    public boolean tableExists(DatabaseConfig config, String tableName) {
        requireIndexName(tableName);
        try (RestClient rc = buildRestClient(config)) {
            try (ElasticsearchTransport tr = new RestClientTransport(rc, new JacksonJsonpMapper())) {
                ElasticsearchClient client = new ElasticsearchClient(tr);
                return Boolean.TRUE.equals(client.indices().exists(e -> e.index(tableName)).value());
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public QueryExecutionResult executeQuery(DatabaseConfig config, String query) {
        long start = System.currentTimeMillis();
        if (query == null) {
            return failure(start, "Query is null");
        }
        String json = query.strip();
        if (json.isEmpty()) {
            return failure(start, "Empty query");
        }
        try (RestClient rc = buildRestClient(config)) {
            try (ElasticsearchTransport tr = new RestClientTransport(rc, new JacksonJsonpMapper())) {
                ElasticsearchClient client = new ElasticsearchClient(tr);
                SearchResponse<JsonData> resp =
                        client.search(
                                s -> s.index("_all").withJson(new StringReader(json)), JsonData.class);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Hit<JsonData> hit : resp.hits().hits()) {
                    JsonData src = hit.source();
                    if (src == null) {
                        rows.add(Map.of("_id", hit.id() != null ? hit.id() : ""));
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> asMap = src.to(Map.class);
                        rows.add(asMap != null ? new LinkedHashMap<>(asMap) : Map.of());
                    }
                }
                return success(start, rows, 0);
            }
        } catch (Exception e) {
            return failure(start, e.getMessage());
        }
    }

    @Override
    public DatabaseSchema getSchema(DatabaseConfig config) {
        List<String> indices = getTableNames(config);
        DatabaseSchema schema = new DatabaseSchema();
        schema.setDbType(DB_TYPE);
        schema.setDatabaseName(config.database() != null ? config.database() : "");
        List<TableSchema> tables = new ArrayList<>();
        for (String index : indices) {
            Map<String, String> types = getColumnTypes(config, index);
            List<ColumnSchema> columns = new ArrayList<>();
            for (Map.Entry<String, String> e : types.entrySet()) {
                ColumnSchema cs = new ColumnSchema();
                cs.setColumnName(e.getKey());
                cs.setDataType(e.getValue());
                cs.setNullable(true);
                cs.setPrimaryKey(false);
                cs.setDefaultValue(null);
                columns.add(cs);
            }
            TableSchema ts = new TableSchema();
            ts.setTableName(index);
            ts.setColumns(columns);
            ts.setPrimaryKeys(List.of());
            ts.setForeignKeys(List.of());
            tables.add(ts);
        }
        schema.setTables(tables);
        return schema;
    }

    private static Map<String, String> flattenMappingFields(GetMappingResponse mr, String indexName) {
        Map<String, String> out = new LinkedHashMap<>();
        var result = mr != null ? mr.result() : null;
        if (result == null || result.isEmpty()) {
            return out;
        }
        IndexMappingRecord rec = result.get(indexName);
        if (rec == null) {
            rec = result.values().iterator().next();
        }
        if (rec == null || rec.mappings() == null || rec.mappings().properties() == null) {
            return out;
        }
        collectProperties("", rec.mappings().properties(), out);
        return out;
    }

    private static void collectProperties(
            String prefix, Map<String, co.elastic.clients.elasticsearch._types.mapping.Property> props,
            Map<String, String> out) {
        if (props == null) {
            return;
        }
        for (Map.Entry<String, co.elastic.clients.elasticsearch._types.mapping.Property> e : props.entrySet()) {
            String name = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            co.elastic.clients.elasticsearch._types.mapping.Property p = e.getValue();
            String typeLabel = propertyTypeLabel(p);
            out.put(name, typeLabel);
            if (p.isObject() && p.object().properties() != null) {
                collectProperties(name, p.object().properties(), out);
            }
            if (p.isNested() && p.nested().properties() != null) {
                collectProperties(name, p.nested().properties(), out);
            }
        }
    }

    private static String propertyTypeLabel(co.elastic.clients.elasticsearch._types.mapping.Property p) {
        if (p == null) {
            return "unknown";
        }
        try {
            return p._kind().jsonValue();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static RestClient buildRestClient(DatabaseConfig config) {
        String scheme = config.port() == 443 ? "https" : "http";
        RestClientBuilder b = RestClient.builder(new HttpHost(config.host(), config.port(), scheme));
        String user = config.username();
        if (user != null && !user.isBlank()) {
            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(user, config.password() != null ? config.password() : ""));
            b.setHttpClientConfigCallback(h -> h.setDefaultCredentialsProvider(cp));
        }
        return b.build();
    }

    private static void requireIndexName(String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("tableName (index) is required");
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
