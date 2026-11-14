package com.teammind.observability.infrastructure;

import io.qdrant.client.QdrantClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component("qdrant")
public class QdrantHealthIndicator implements HealthIndicator {

    private final QdrantClient qdrantClient;
    private final Duration healthTimeout;

    public QdrantHealthIndicator(QdrantClient qdrantClient,
            @Value("${teammind.qdrant.health-timeout:3s}") Duration healthTimeout) {
        this.qdrantClient = qdrantClient;
        this.healthTimeout = healthTimeout;
    }

    @Override
    public Health health() {
        try {
            var reply = qdrantClient.healthCheckAsync(healthTimeout).get();
            return Health.up()
                    .withDetail("version", reply.getVersion())
                    .withDetail("connection", "gRPC healthCheck succeeded")
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable(exception);
        } catch (Exception exception) {
            return unavailable(exception);
        }
    }

    private Health unavailable(Exception exception) {
        // Do not expose credentials, request payloads or remote exception messages.
        return Health.down()
                .withDetail("connection", "gRPC healthCheck failed")
                .withDetail("error", rootCauseType(exception).getSimpleName())
                .build();
    }

    private Class<?> rootCauseType(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass();
    }
}
