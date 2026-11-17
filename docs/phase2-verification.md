# Phase 2 验收记录

## 已实现范围

Phase 2 已实现知识库创建、查询、修改和删除；PDF、DOCX、TXT、Markdown 上传；异步解析、分块、Qwen Embedding 和 Qdrant 入库；Dense 向量检索；Qwen RAG 问答；数据库 Chunk 引用、PDF 页码和文档版本返回；文档重处理与删除清理。

实现同时修复了以下基础缺陷：PDF 与 Tika 解析器重复注册、分块先入库导致 `vector_id` 为空、删除文档残留分块、失败原因无法被 `null` 清空、接口暴露服务器文件路径，以及 Qdrant 集合不存在时检索和删除返回异常。

## 自动化验证结果

2026-11-15 将当前源码复制到不含历史构建产物的临时目录，使用 Java 21.0.13、Maven 3.9.11 执行：

```bash
mvn clean package
```

结果为 `BUILD SUCCESS`，7 个 Maven 模块全部打包成功，可执行 Spring Boot JAR 已生成。

默认测试共 **22 项，全部通过**：

- 统一响应：1 项
- 文档解析、解析器注册、分块和异步处理顺序：8 项
- Qwen 客户端请求、响应维度、错误边界和 RAG 引用校验：6 项
- Spring 上下文、接口 DTO 脱敏、参数校验和统一异常：6 项
- Ollama 本地模型 profile 配置：1 项

Qwen 网关测试使用本机临时 HTTP 服务，实际验证了请求模型名、输入、向量维度、响应顺序、对话结果和错误转换；没有调用真实付费接口。

## 尚待真实环境验收

当前命令执行沙箱没有 Docker 服务，因此以下项目已编写自动化测试并成功编译，但尚未实际执行：

- PostgreSQL 迁移和 Phase 2 唯一索引
- Redis 连接
- Qdrant gRPC、集合创建、向量 upsert 和检索
- PDF 真实上传到异步处理、引用问答、删除清理的完整链路
- Spring Boot 真实 HTTP 健康接口、OpenAPI 和 Swagger UI

在项目根目录、Docker 可用的本机执行：

```bash
./scripts/verify-infrastructure.sh
```

该脚本启动隔离的 PostgreSQL、Redis、Qdrant，运行 5 项集成测试，结束后自动删除测试服务和临时数据。Phase 2 全链路测试使用真实 PDF、PostgreSQL 和 Qdrant，仅将外部 Qwen 替换为本地确定性模型，避免依赖密钥和产生调用费用。

真实 Qwen Embedding 与 Chat 还需要配置有效 `QWEN_API_KEY` 后，使用 README 中的上传和问答请求人工验收；也可以按 [Ollama 本地模型部署说明](local-models.md) 使用 `local,ollama` 双 profile 完全本地验收。以下任一情况都应视为验收未完成：文档未达到 `COMPLETED`、检索无结果、模型未返回引用、引用不是真实 Chunk，或健康接口中 PostgreSQL/Qdrant 不是 `UP`。

## 已知限制

当前没有登录认证。`ownerId` 是客户端传入的开发期归属参数，不能作为权限控制。BM25、RRF、Reranker、Evidence Board、Agent、记忆、SSE 流式回答和前端页面属于后续阶段。知识库权限关联表、文档历史版本回滚、对象存储、限流和文件安全扫描尚未实现。

沙箱不允许删除挂载目录旧文件，因此 `team-mind-web/src/main/java/com/teammind/web/health/QdrantHealthIndicator.java` 是早期迁移留下的空文件；实际实现在 `team-mind-observability`，可在本机删除该空文件及目录。主资源中的 `application-test.yml` 同样只是说明占位，测试配置位于 `src/test/resources`。
