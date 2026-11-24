package com.teammind.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** OpenAPI 文档元信息：统一说明当前阶段范围、调用方式和安全边界。 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI teamMindOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TeamMind API")
                        .version("v1")
                        .description("""
                                TeamMind 企业级研发知识库 Agent 平台，当前处于 Phase 3（高级 RAG）。

                                已实现能力：知识库管理、文档上传与异步解析分块、Dense/BM25/HYBRID/HYBRID_RERANK
                                四种检索策略、RRF 融合、Reranker 重排、有效文档版本过滤、带真实引用的知识库问答、
                                Evidence Board 证据回溯，以及 PostgreSQL/Redis/Qdrant 健康检查。

                                统一响应格式为 {code, message, data, path, timestamp, errors}：
                                code 为 "0" 表示成功，其余为稳定业务错误码（如 40001 参数校验失败、40401 资源不存在、
                                40901 数据冲突、42201 业务规则失败、50301 依赖服务不可用）。

                                注意：当前版本尚未接入登录认证，请求中的 ownerId 只是开发期归属参数，
                                用于隔离数据，不能视为安全认证。Agent、长期记忆、完整链路追踪和前端业务页面属于后续阶段。
                                """))
                .servers(List.of(new Server().url("http://localhost:8080").description("本地开发环境")));
    }
}
