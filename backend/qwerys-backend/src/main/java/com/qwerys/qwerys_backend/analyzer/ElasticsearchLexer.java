package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tokenizes Elasticsearch Query DSL JSON and classifies well-known clause keys into
 * {@link EsTokenType#QUERY_KEY} or {@link EsTokenType#AGG_KEY}.
 *
 * <p>Keys that appear both as query types and aggregation types (for example {@code terms},
 * {@code range}, {@code nested}, {@code geo_distance}) are emitted as {@link EsTokenType#STRING}
 * to avoid misleading classification without parse context.
 */
public final class ElasticsearchLexer {

    private static final Set<String> QUERY_KEYS;
    private static final Set<String> AGG_KEYS;
    /**
     * Aggregation names that must not appear as direct keys under {@code "query"} (see ES-STRUCT-001).
     * Names that are also valid query clause keys (e.g. {@code terms}, {@code range}, {@code nested})
     * are excluded so legitimate queries are not flagged.
     */
    private static final Set<String> AGG_KEYS_ILLEGAL_AT_QUERY_ROOT;

    static {
        Set<String> q = new HashSet<>();
        String[] ql = {
                "query",
                "match", "match_all", "match_phrase", "match_phrase_prefix", "multi_match",
                "match_bool_prefix", "combined_fields",
                "query_string", "simple_query_string",
                "term", "terms", "range", "exists", "prefix", "wildcard", "regexp", "fuzzy",
                "ids", "bool", "nested", "has_child", "has_parent", "script",
                "geo_shape", "geo_bounding_box", "geo_distance", "geo_polygon", "geo_grid",
                "more_like_this", "knn", "function_score", "dis_max", "boosting",
                "constant_score", "percolate", "wrapper", "script_score", "intervals",
                "distance_feature", "pinned", "shape",
                "span_near", "span_term", "span_or", "span_not", "span_first", "span_multi",
                "rank_feature", "rank_features", "rule", "semantic", "sparse_vector", "weighted_tokens",
        };
        Collections.addAll(q, ql);

        Set<String> a = new HashSet<>();
        String[] al = {
                "aggs", "aggregations",
                "terms", "range", "filter", "filters", "global", "missing",
                "date_histogram", "auto_date_histogram", "date_range", "ip_range",
                "histogram",
                "nested", "reverse_nested", "children", "parent",
                "sampler", "diversified_sampler", "significant_terms", "significant_text",
                "geo_distance", "geohash_grid", "geotile_grid", "adjacency_matrix",
                "avg", "sum", "min", "max", "stats", "extended_stats",
                "percentiles", "percentile_ranks",
                "cardinality", "value_count", "top_hits", "top_metrics", "scripted_metric",
                "median_absolute_deviation", "rate", "t_test",
                "avg_bucket", "sum_bucket", "max_bucket", "min_bucket",
                "stats_bucket", "extended_stats_bucket", "percentiles_bucket",
                "bucket_script", "bucket_selector", "bucket_sort", "bucket_count_ks_test",
                "bucket_correlation", "cumulative_sum", "cumulative_cardinality",
                "derivative", "serial_diff", "moving_avg", "moving_fn", "moving_percentiles",
                "normalize", "inference",
                "weighted_avg", "variable_width_histogram", "multi_terms", "rare_terms",
                "ip_prefix", "frequent_items",
        };
        Collections.addAll(a, al);

        QUERY_KEYS = Collections.unmodifiableSet(q);
        AGG_KEYS = Collections.unmodifiableSet(a);

        Set<String> struct = new HashSet<>();
        String[] structAggNames = {
                "date_histogram", "auto_date_histogram", "date_range", "avg_bucket", "sum_bucket",
                "max_bucket", "min_bucket", "terms", "histogram", "range", "stats", "extended_stats",
                "percentiles", "percentile_ranks", "cardinality", "value_count", "top_hits",
                "scripted_metric", "top_metrics", "t_test", "median_absolute_deviation", "rate",
                "moving_avg", "moving_fn", "derivative", "cumulative_sum", "bucket_script",
                "bucket_selector", "geohash_grid", "geotile_grid", "significant_terms", "nested",
                "reverse_nested", "children", "sampler", "diversified_sampler", "parent",
        };
        Collections.addAll(struct, structAggNames);
        struct.removeAll(QUERY_KEYS);
        AGG_KEYS_ILLEGAL_AT_QUERY_ROOT = Collections.unmodifiableSet(struct);
    }

    public static boolean isForbiddenAggregationKeyAtQueryRoot(String key) {
        return key != null && AGG_KEYS_ILLEGAL_AT_QUERY_ROOT.contains(key);
    }

    private final String input;
    private int pos;
    private int line;
    private int column;

    public ElasticsearchLexer(String input) {
        this.input = input != null ? input : "";
        this.pos = 0;
        this.line = 1;
        this.column = 1;
    }

    public List<ElasticsearchToken> tokenize() {
        List<ElasticsearchToken> out = new ArrayList<>(128);
        skipWs();
        while (!isEof()) {
            int tl = line;
            int tc = column;
            char c = peek();
            switch (c) {
                case '{' -> {
                    advance();
                    out.add(new ElasticsearchToken(EsTokenType.BRACE_OPEN, "{", tl, tc));
                }
                case '}' -> {
                    advance();
                    out.add(new ElasticsearchToken(EsTokenType.BRACE_CLOSE, "}", tl, tc));
                }
                case '[' -> {
                    advance();
                    out.add(new ElasticsearchToken(EsTokenType.BRACKET_OPEN, "[", tl, tc));
                }
                case ']' -> {
                    advance();
                    out.add(new ElasticsearchToken(EsTokenType.BRACKET_CLOSE, "]", tl, tc));
                }
                case ':' -> {
                    advance();
                    out.add(new ElasticsearchToken(EsTokenType.COLON, ":", tl, tc));
                }
                case ',' -> {
                    advance();
                    out.add(new ElasticsearchToken(EsTokenType.COMMA, ",", tl, tc));
                }
                case '"' -> out.add(readString(tl, tc));
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                        out.add(readNumber(tl, tc));
                default -> {
                    if (Character.isDigit(c)) {
                        out.add(readNumber(tl, tc));
                    } else if (isIdentStart(c)) {
                        out.add(readWord(tl, tc));
                    } else {
                        advance();
                        out.add(new ElasticsearchToken(EsTokenType.UNKNOWN, String.valueOf(c), tl, tc));
                    }
                }
            }
            skipWs();
        }
        out.add(new ElasticsearchToken(EsTokenType.EOF, "", line, column));
        return out;
    }

    private static EsTokenType classifyQuotedKey(String v) {
        boolean query = QUERY_KEYS.contains(v);
        boolean agg = AGG_KEYS.contains(v);
        if (query && agg) {
            return EsTokenType.STRING;
        }
        if (query) {
            return EsTokenType.QUERY_KEY;
        }
        if (agg) {
            return EsTokenType.AGG_KEY;
        }
        return EsTokenType.STRING;
    }

    private ElasticsearchToken readString(int tl, int tc) {
        advance(); // opening "
        StringBuilder sb = new StringBuilder();
        while (!isEof()) {
            char c = peek();
            if (c == '"') {
                advance();
                String v = sb.toString();
                return new ElasticsearchToken(classifyQuotedKey(v), v, tl, tc);
            }
            if (c == '\\') {
                advance();
                if (isEof()) {
                    throw new LexException("Unterminated string escape", tl, tc);
                }
                char esc = peek();
                advance();
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(readUnicodeEscape(tl, tc));
                    default ->
                            throw new LexException("Invalid escape sequence \\" + esc, tl, tc);
                }
            } else {
                if (c < 0x20) {
                    throw new LexException("Control character inside string literal", tl, tc);
                }
                advance();
                sb.append(c);
            }
        }
        throw new LexException("Unterminated string literal", tl, tc);
    }

    private char readUnicodeEscape(int tl, int tc) {
        int code = 0;
        for (int i = 0; i < 4; i++) {
            if (isEof()) {
                throw new LexException("Truncated unicode escape", tl, tc);
            }
            char h = peek();
            advance();
            int d = hexValue(h);
            if (d < 0) {
                throw new LexException("Bad hex digit in \\uXXXX", tl, tc);
            }
            code = (code << 4) | d;
        }
        return (char) code;
    }

    private static int hexValue(char h) {
        return switch (h) {
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> h - '0';
            case 'a', 'A' -> 10;
            case 'b', 'B' -> 11;
            case 'c', 'C' -> 12;
            case 'd', 'D' -> 13;
            case 'e', 'E' -> 14;
            case 'f', 'F' -> 15;
            default -> -1;
        };
    }

    private ElasticsearchToken readNumber(int tl, int tc) {
        int start = pos;
        if (peek() == '-') {
            advance();
        }
        while (!isEof() && Character.isDigit(peek())) {
            advance();
        }
        if (!isEof() && peek() == '.') {
            advance();
            while (!isEof() && Character.isDigit(peek())) {
                advance();
            }
        }
        if (!isEof() && (peek() == 'e' || peek() == 'E')) {
            advance();
            if (!isEof() && (peek() == '+' || peek() == '-')) {
                advance();
            }
            while (!isEof() && Character.isDigit(peek())) {
                advance();
            }
        }
        String lex = input.substring(start, pos);
        return new ElasticsearchToken(EsTokenType.NUMBER, lex, tl, tc);
    }

    private ElasticsearchToken readWord(int tl, int tc) {
        int start = pos;
        while (!isEof() && isIdentPart(peek())) {
            advance();
        }
        String w = input.substring(start, pos);
        return switch (w) {
            case "true", "false" ->
                    new ElasticsearchToken(EsTokenType.BOOLEAN, w, tl, tc);
            case "null" -> new ElasticsearchToken(EsTokenType.NULL, w, tl, tc);
            default -> new ElasticsearchToken(EsTokenType.UNKNOWN, w, tl, tc);
        };
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || Character.isDigit(c);
    }

    private void skipWs() {
        while (!isEof()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                advance();
            } else {
                break;
            }
        }
    }

    private boolean isEof() {
        return pos >= input.length();
    }

    private char peek() {
        return input.charAt(pos);
    }

    private void advance() {
        if (!isEof()) {
            char c = input.charAt(pos++);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
    }

    public static final class LexException extends RuntimeException {
        private final int line;
        private final int column;

        public LexException(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }

        public int line() {
            return line;
        }

        public int column() {
            return column;
        }
    }
}
