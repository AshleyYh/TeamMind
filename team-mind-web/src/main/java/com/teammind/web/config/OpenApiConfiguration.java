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
                .description("Phase 2 基础 RAG 接口：知识库、文档入库与带引用问答；尚未实现混合检索、Agent 和记忆。"));
    }
}
