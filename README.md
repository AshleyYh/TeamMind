# TeamMind

企业级研发知识库 Agent 平台。当前完成 **Phase 3：高级 RAG**，包括文档入库、Dense + BM25 并行检索、RRF 融合、BGE 系列 Reranker、有效版本过滤、带真实引用的问答和 Evidence Board。Agent、长期记忆、完整链路追踪和前端业务页面尚未实现。

详细设计见 [架构说明](docs/architecture.md)，阶段验收见 [Phase 3 验收记录](docs/phase3-verification.md)，原始需求见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 技术基线

Java 21、Spring Boot 3.5.7、Maven 3.9.11、PostgreSQL 16、Redis 7、Qdrant 1.16.0、MyBatis-Plus 3.5.14、Flyway、SpringDoc OpenAPI 2.8.14。Embedding 与对话模型通过 DashScope 的 Qwen OpenAI 兼容接口接入。

## 本地启动

在项目根目录执行：

```bash
cp .env.example .env
docker compose up -d --wait

export QWEN_API_KEY="你的 DashScope API Key"
./mvnw -pl team-mind-web -am package -DskipTests
java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local
```

`.env` 只会被 Docker Compose 自动读取，Java 进程不会自动加载它；`QWEN_API_KEY` 必须通过终端、运行配置或密钥管理服务注入。缺少密钥时应用仍可启动，但文档向量化和问答会返回明确的 `50301` 服务不可用错误。

`local` profile 会创建一个 ID 为 `1` 的本地占位用户，仅用于满足知识库外键和开发期调用。该用户没有可用密码，也不会在测试或生产环境创建。

Windows 使用 `mvnw.cmd` 代替 `./mvnw`。

### 使用本地 Ollama 模型

默认推荐 `qwen3.5:9b` 负责回答、`qwen3-embedding:0.6b` 负责 1024 维向量。安装并拉取模型后，使用 `local,ollama` 双 profile 启动：

```bash
ollama pull qwen3-embedding:0.6b
ollama pull qwen3.5:9b
./mvnw -pl team-mind-web -am package -DskipTests
java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local,ollama
```

本地模式使用独立 Qdrant 集合 `team_mind_chunks_local_qwen3_1024`，不能复用云端 Embedding 生成的旧集合；已有文档需要重新上传或逐篇调用重处理接口来重建本地向量。完整部署、显存检查和接口自检步骤见 [本地 Ollama 部署说明](docs/local-models.md)。

## 使用流程

Swagger UI：<http://localhost:8080/api/v1/swagger-ui.html>。

先创建知识库：

```bash
curl -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H 'Content-Type: application/json' \
  -d '{"name":"研发知识库","description":"研发文档","ownerId":1}'
```

上传文档并记录返回的知识库 ID：

```bash
curl -X POST "http://localhost:8080/api/v1/documents/upload?knowledgeBaseId=知识库ID&ownerId=1" \
  -F "file=@./示例.pdf"
```

上传接口只负责保存文件和调度异步处理，立即返回状态 `PENDING`。轮询处理状态：

```bash
curl "http://localhost:8080/api/v1/documents/文档ID/status?ownerId=1"
```

状态变为 `COMPLETED` 后即可问答：

```bash
curl -X POST http://localhost:8080/api/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"message":"订单模块最大连接数是多少？","knowledgeBaseIds":[知识库ID],"ownerId":1,"topK":5}'
```

回答包含 `citations`、`traceId` 和 `strategy`。每个引用都来自数据库中的真实 Chunk，并携带文档 ID、Chunk ID、版本、页码、原文、Dense/BM25 排名、RRF 与重排分数。服务端会拒绝模型返回越界引用编号；没有检索证据时不会调用模型，而是返回“没有找到足够依据”。

默认策略为 `HYBRID_RERANK`，需要 `QWEN_API_KEY`（或单独的 `RERANK_API_KEY`）。可通过请求中的 `strategy` 改为 `DENSE`、`BM25` 或 `HYBRID`，也可独立调用策略检索：

```bash
curl -X POST http://localhost:8080/api/v1/retrieval/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"HikariCP 最大连接数","knowledgeBaseIds":[1],"ownerId":1,"topK":5,"strategy":"BM25"}'
```

按问答返回的 `traceId` 查看证据快照和最终引用状态：

```bash
curl 'http://localhost:8080/api/v1/evidence/替换为traceId?ownerId=1'
```

**升级注意：** Phase 2 已入库文档需要逐篇调用重处理接口，补齐 BM25 检索词元后才能参与关键词召回。

## 接口范围

知识库接口支持创建、列表、详情、修改和删除。文档接口支持上传、列表、详情、状态、重处理和删除。问答接口为 `POST /api/v1/chat/completions`。接口返回 DTO，不暴露服务器文件路径。

Phase 2 尚未接入身份认证，请求中的 `ownerId` 是开发阶段的显式归属参数，**不能视为安全认证**。上线前必须由 Phase 1 规划的用户与权限模块替换该参数，并从登录上下文获取用户身份。

## 处理机制

支持 PDF、DOCX、TXT、Markdown，单文件默认上限 20 MB。PDF 按实际页提取文本，其他格式使用 Tika。文本按配置分块并保留重叠，分批生成 Embedding 后写入 Qdrant，Chunk 元数据写入 PostgreSQL。Qdrant Point ID 由文档、版本和分块序号确定性生成，重试使用 upsert，不会重复累积向量。

文档处理在数据库事务提交后异步执行。状态流转为 `PENDING → PROCESSING → COMPLETED/FAILED`，失败原因对业务错误可见，未知异常只记录服务端日志。重新处理会清理旧分块和向量；删除文档会同步清理数据库分块和向量。

## 构建与测试

```bash
./mvnw clean package
```

真实服务集成测试使用独立端口和临时存储，结束后自动删除：

```bash
./scripts/verify-infrastructure.sh
```

该脚本启动 PostgreSQL `15432`、Redis `16379`、Qdrant `16333/16334`，运行基础设施连接、Flyway、HTTP 健康接口以及“PDF 上传→分块→向量入库→BM25/RRF/重排→带引用回答→Evidence→删除清理”的 Phase 3 全链路测试。外部模型在集成测试中替换为本地确定性实现，不消耗 Qwen 配额。

## 主要配置

`DB_*`、`REDIS_*`、`QDRANT_*` 控制基础设施连接；`QWEN_API_KEY`、`QWEN_BASE_URL`、`QWEN_EMBEDDING_MODEL`、`QWEN_CHAT_MODEL`、`EMBEDDING_DIMENSION` 控制模型；`DOCUMENT_*` 控制上传、分块和批处理；`RAG_*` 控制召回数量、策略、RRF 和并行超时；`RERANK_*` 控制重排服务，密钥默认复用 `QWEN_API_KEY`。完整默认值见 [application.yml](team-mind-web/src/main/resources/application.yml)。

上传文件存放在 `./data/documents`，已被 Git 忽略。停止本地基础设施使用 `docker compose down`；只有明确需要删除本地数据时才添加 `--volumes`。
