package com.teammind.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI teamMindOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TeamMind API")
                .version("v1")
                .description("Phase 3 高级 RAG 接口：知识库、文档入库、策略检索、混合重排问答与 Evidence；Agent 和记忆属于后续阶段。"));
    }
}
