package com.teammind.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "management.health.qdrant.enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState"
})
class TeamMindApplicationTests {

    @Test
    void contextLoads() {
    }
}
