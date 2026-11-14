package com.teammind.web.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class InfrastructureConfiguration {

    @Bean(destroyMethod = "close")
    QdrantClient qdrantClient(QdrantProperties properties) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                        properties.host(), properties.port(), properties.useTls())
                .withTimeout(properties.requestTimeout());
        if (StringUtils.hasText(properties.apiKey())) {
            builder.withApiKey(properties.apiKey());
        }
        return new QdrantClient(builder.build());
    }
}
