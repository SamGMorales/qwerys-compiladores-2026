package com.qwerys.qwerys_backend.controller;

import com.qwerys.qwerys_backend.config.JwtUtil;
import com.qwerys.qwerys_backend.model.MultiStatementAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryRequest;
import com.qwerys.qwerys_backend.repository.UserRepository;
import com.qwerys.qwerys_backend.service.QueryAnalysisService;
import com.qwerys.qwerys_backend.student.StudentExplanationEnricher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/queries")
@CrossOrigin(origins = "http://localhost:4200")
public class QueryController {

    private static final Set<String> VALID_DATABASE_TYPES = Set.of(
            "mysql", "postgresql", "sqlite", "sqlserver", "oracle",
            "mongodb", "redis", "cassandra", "dynamodb", "elasticsearch"
    );

    private final QueryAnalysisService queryAnalysisService;
    private final StudentExplanationEnricher studentExplanationEnricher;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public QueryController(
            QueryAnalysisService queryAnalysisService,
            StudentExplanationEnricher studentExplanationEnricher,
            JwtUtil jwtUtil,
            UserRepository userRepository) {
        this.queryAnalysisService = queryAnalysisService;
        this.studentExplanationEnricher = studentExplanationEnricher;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    private static boolean isAllowedDatabaseType(String databaseType) {
        if (databaseType == null || databaseType.isBlank()) {
            return false;
        }
        String t = databaseType.strip().toLowerCase(Locale.ROOT);
        if (VALID_DATABASE_TYPES.contains(t)) {
            return true;
        }
        if ("custom".equals(t)) {
            return true;
        }
        return t.startsWith("custom::");
    }

    @PostMapping("/analyze")
    @ResponseStatus(HttpStatus.OK)
    public QueryAnalysisResponse analyze(
            @RequestBody QueryRequest request,
            @RequestHeader(value = "X-Expert-Mode", defaultValue = "false") String expertModeHeader,
            @RequestHeader(value = "X-Student-Mode", defaultValue = "false") String studentModeHeader) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("The 'query' field must not be null or empty");
        }
        if (!isAllowedDatabaseType(request.databaseType())) {
            throw new IllegalArgumentException(
                    "Invalid 'databaseType'. Must be one of: mysql, postgresql, sqlite, sqlserver, oracle, "
                            + "mongodb, redis, cassandra, dynamodb, elasticsearch, custom, or custom::<label>::<base>");
        }
        QueryAnalysisResponse response =
                queryAnalysisService.analyzeQuery(request, isExpertMode(expertModeHeader));
        if (isStudentMode(studentModeHeader)) {
            response = studentExplanationEnricher.enrich(
                    response, StudentExplanationEnricher.resolveUiLocale(request));
        }
        return response;
    }

    @PostMapping("/analyze-multi")
    @ResponseStatus(HttpStatus.OK)
    public MultiStatementAnalysisResponse analyzeMulti(
            @RequestBody QueryRequest request,
            @RequestHeader(value = "X-Expert-Mode", defaultValue = "false") String expertModeHeader,
            @RequestHeader(value = "X-Student-Mode", defaultValue = "false") String studentModeHeader) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("The 'query' field must not be null or empty");
        }
        if (!isAllowedDatabaseType(request.databaseType())) {
            throw new IllegalArgumentException(
                    "Invalid 'databaseType'. Must be one of: mysql, postgresql, sqlite, sqlserver, oracle, "
                            + "mongodb, redis, cassandra, dynamodb, elasticsearch, custom, or custom::<label>::<base>");
        }
        MultiStatementAnalysisResponse response =
                queryAnalysisService.analyzeMultiStatement(request, isExpertMode(expertModeHeader));
        if (isStudentMode(studentModeHeader)) {
            response = studentExplanationEnricher.enrich(
                    response, StudentExplanationEnricher.resolveUiLocale(request));
        }
        return response;
    }

    private static boolean isExpertMode(String headerValue) {
        return headerValue != null && "true".equalsIgnoreCase(headerValue.strip());
    }

    private static boolean isStudentMode(String headerValue) {
        return headerValue != null && "true".equalsIgnoreCase(headerValue.strip());
    }

    @GetMapping("/engines")
    public ResponseEntity<List<Map<String, String>>> getSupportedEngines(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        List<Map<String, String>> engines = new ArrayList<>(List.of(
                Map.of("id", "mysql", "name", "MySQL"),
                Map.of("id", "postgresql", "name", "PostgreSQL"),
                Map.of("id", "sqlite", "name", "SQLite"),
                Map.of("id", "sqlserver", "name", "SQL Server"),
                Map.of("id", "oracle", "name", "Oracle"),
                Map.of("id", "mongodb", "name", "MongoDB"),
                Map.of("id", "redis", "name", "Redis"),
                Map.of("id", "cassandra", "name", "Cassandra"),
                Map.of("id", "dynamodb", "name", "DynamoDB"),
                Map.of("id", "elasticsearch", "name", "Elasticsearch")
        ));

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                if (jwtUtil.isTokenValid(token)) {
                    String email = jwtUtil.extractEmail(token);
                    userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
                        if (user.getCustomDatabases() != null) {
                            for (String entry : user.getCustomDatabases()) {
                                String name = entry.contains("::") ? entry.split("::", 2)[0] : entry;
                                String base = entry.contains("::") ? entry.split("::", 2)[1] : "mysql";
                                engines.add(Map.of(
                                        "id", "custom::" + entry,
                                        "name", name,
                                        "base", base,
                                        "custom", "true"));
                            }
                        }
                    });
                }
            } catch (Exception ignored) {
                // Token inválido — solo motores predefinidos
            }
        }

        return ResponseEntity.ok(engines);
    }

    @GetMapping("/health")
    @ResponseStatus(HttpStatus.OK)
    public String health() {
        return "QWERYS Backend Running!";
    }
}
