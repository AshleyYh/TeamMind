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

/**
 * Opt-in real-service verification. Run scripts/verify-infrastructure.sh after
 * starting the isolated test stack in docker-compose.test.yml.
 */
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

        // A second migration on the same database must not execute V1 again.
        var result = flyway.migrate();
        assertThat(result.migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(1);
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
