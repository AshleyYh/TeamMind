package com.teammind.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class InfrastructureHttpIntegrationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void exposesOpenApiAndHealthyInfrastructure() {
        var openApi = restTemplate.getForEntity("/api/v1/api-docs", String.class);
        assertThat(openApi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openApi.getBody()).contains("TeamMind API");

        var swagger = restTemplate.getForEntity("/api/v1/swagger-ui.html", String.class);
        assertThat(swagger.getStatusCode()).isEqualTo(HttpStatus.OK);

        var health = restTemplate.exchange("/api/v1/actuator/health", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() { });
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("status", "UP");
        assertThat(health.getBody().toString()).contains("qdrant", "redis", "db");
    }
}
