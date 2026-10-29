package com.aiworkbench.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Java API 健康检查控制器。
 *
 * <p>健康检查同时验证 Java 到 PostgreSQL 和 Python RAG 服务的连通性，
 * 便于本地启动后快速判断整条服务链是否可用。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "系统", description = "本地服务健康检查。")
public class HealthController {

    /** 用于验证 PostgreSQL 连通性的 JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** 用于调用 Python RAG 健康接口的 HTTP 客户端。 */
    private final RestClient pythonRagClient;

    /**
     * 创建健康检查控制器。
     *
     * @param jdbcTemplate Spring JDBC 模板
     * @param restClientBuilder HTTP 客户端构造器
     * @param pythonRagBaseUrl Python RAG 服务基础地址
     */
    public HealthController(
            JdbcTemplate jdbcTemplate,
            RestClient.Builder restClientBuilder,
            @Value("${workbench.python-rag-base-url}") String pythonRagBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.pythonRagClient = restClientBuilder.baseUrl(pythonRagBaseUrl).build();
    }

    /**
     * 返回 Java、数据库和 Python 服务的综合健康状态。
     *
     * @return 健康状态响应；所有依赖均可用时返回 200，否则返回 503
     */
    @GetMapping("/health")
    @Operation(summary = "查询本地服务健康状态", description = "同时检查 Java API、PostgreSQL 和 Python RAG 服务。")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "java-api");
        response.put("timestamp", Instant.now().toString());

        boolean databaseReady = isDatabaseReady();
        boolean pythonRagReady = isPythonRagReady();
        response.put("database", databaseReady ? "UP" : "DOWN");
        response.put("pythonRag", pythonRagReady ? "UP" : "DOWN");
        response.put("status", databaseReady && pythonRagReady ? "UP" : "DOWN");

        return ResponseEntity.status(databaseReady && pythonRagReady
                        ? HttpStatus.OK
                        : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }

    /**
     * 使用最小 SQL 验证数据库连接。
     *
     * @return 数据库是否可用
     */
    private boolean isDatabaseReady() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(result);
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 调用 Python RAG 服务的健康接口。
     *
     * @return Python RAG 服务是否返回 {@code UP}
     */
    private boolean isPythonRagReady() {
        try {
            Map<?, ?> pythonResponse = pythonRagClient.get()
                    .uri("/internal/v1/health")
                    .retrieve()
                    .body(Map.class);
            return pythonResponse != null && "UP".equals(pythonResponse.get("status"));
        } catch (Exception exception) {
            return false;
        }
    }
}
