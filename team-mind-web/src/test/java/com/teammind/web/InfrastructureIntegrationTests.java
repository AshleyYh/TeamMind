package com.teammind.web;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实基础设施验证：启动隔离测试环境后，通过 scripts/verify-infrastructure.sh 执行。 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class InfrastructureIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    @Qualifier("qdrant")
    private HealthIndicator qdrantHealthIndicator;

    @Autowired
    private Flyway flyway;

    @Test
    void connectsToPostgreSqlAndRunsIdempotentMigrations() {
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tables).contains("sys_user", "knowledge_base", "knowledge_document",
                "knowledge_chunk", "ai_conversation", "ai_memory", "ai_trace",
                "ai_trace_span", "ai_evidence");

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(indexes).contains("uk_document_active_name", "uk_knowledge_base_owner_name_active",
                "idx_chunk_search_vector", "idx_evidence_owner_trace");
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'ai_evidence'", String.class);
        assertThat(columns).contains("owner_id", "document_name", "content", "dense_rank", "bm25_rank",
                "rrf_score", "reranked", "cited");

        // 同一数据库重复迁移不能再次执行历史脚本。
        var result = flyway.migrate();
        assertThat(result.migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(3);
    }

    @Test
    void connectsToRedisAndSupportsPing() {
        try (var connection = redisConnectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @Test
    void connectsToQdrantUsingGrpcHealthCheck() {
        Health health = qdrantHealthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UP);
        assertThat(health.getDetails()).containsEntry("connection", "gRPC healthCheck succeeded");
    }
}
