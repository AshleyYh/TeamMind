# TeamMind

企业级研发知识库 Agent 平台。当前完成 **Phase 3：高级 RAG**，已经具备从文档入库到带真实引用问答的完整链路：文档解析与分块、Qwen Embedding、Qdrant 向量检索、BM25 关键词召回、Dense + BM25 并行检索、RRF 融合、Reranker 重排、有效版本过滤、Evidence Board 证据回溯。

Agent 任务规划、长期记忆、完整链路追踪、量化评测和前端业务页面尚未实现。

详细设计见 [架构说明](docs/architecture.md)，阶段验收见 [Phase 3 验收记录](docs/phase3-verification.md)，原始需求见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 当前进度

| 阶段 | 状态 | 已完成内容 |
| --- | --- | --- |
| Phase 1 | ✅ 完成 | 多模块工程骨架、PostgreSQL/Redis/Qdrant 基础设施、Flyway 迁移、统一响应与异常处理、健康检查、Swagger 文档 |
| Phase 2 | ✅ 完成 | 知识库 CRUD、文档上传与异步处理、PDF/DOCX/TXT/Markdown 解析、文本分块与重叠、Embedding 入库、基础 RAG 问答与引用 |
| Phase 3 | ✅ 完成 | BM25 关键词召回、Dense + BM25 并行检索、RRF 融合、BGE/Qwen Reranker、文档版本过滤、四种检索策略、Evidence Board |
| Phase 4+ | ⏳ 未开始 | ReAct Agent、短期/长期记忆、完整 Trace/Span、量化评测、SSE 流式输出、登录认证、前端业务页面 |

## 已实现功能

**知识库管理**：创建、列表、详情、修改和逻辑删除。同一用户下名称唯一；知识库中仍有文档时禁止删除。

**文档入库**：支持 PDF、DOCX、TXT 和 Markdown，单文件默认上限 20 MB。PDF 按实际页提取文本，其他格式使用 Apache Tika。上传后异步执行“解析 → 分块 → 生成 Embedding → 写入 Qdrant → 写入 PostgreSQL”，状态流转为 `PENDING → PROCESSING → COMPLETED/FAILED`。同名文档重新上传会递增版本；重新处理会清理旧分块和向量；删除文档会同步清理 Qdrant 向量。

**高级检索**：支持四种策略。

| 策略 | 说明 |
| --- | --- |
| `DENSE` | 仅向量检索：Embedding → Qdrant → 加载有效分块 |
| `BM25` | 仅关键词检索：中文二元词/英文词元 → PostgreSQL BM25 |
| `HYBRID` | Dense 与 BM25 并行召回，再用 RRF 融合 |
| `HYBRID_RERANK` | 在 HYBRID 基础上调用 Reranker 重排，默认策略 |

每个结果保留 `denseRank`、`bm25Rank`、`denseScore`、`bm25Score`、`rrfScore`、`rerankScore`，便于比较各阶段效果。BM25 使用 `k1=1.2`、`b=0.75` 与 Robertson IDF，文档频率和平均长度只基于所选知识库中的当前有效分块计算。

**知识库问答**：默认执行混合检索与重排，把带编号的证据交给 Qwen 生成回答。每个引用都来自数据库中的真实 Chunk，包含文档 ID、Chunk ID、版本、页码、原文、两路排名和融合/重排分数。服务端会拒绝模型返回的越界引用编号；没有检索证据时不会调用模型，而是直接返回“没有找到足够依据”。

**Evidence Board**：每次检索或问答返回 32 位十六进制 `traceId`，并持久化最终 Top K 证据快照。Evidence 保存原文、页码、版本、两路排名、融合/重排分数、是否重排和是否被回答实际引用。历史证据采用不可变快照，文档重处理或删除后仍可追溯。

**基础设施与可观测性**：PostgreSQL 保存业务元数据、分块和检索词元；Qdrant 保存向量；Redis 已完成连接与健康检查，为后续会话和缓存预留；Actuator 提供 liveness/readiness 健康检查，readiness 会检查数据库、Redis 和 Qdrant。

## 技术基线

Java 21、Spring Boot 3.5.7、Maven 3.9.11、PostgreSQL 16、Redis 7、Qdrant 1.16.0、MyBatis-Plus 3.5.14、Flyway、SpringDoc OpenAPI 2.8.14。Embedding 与对话模型通过 DashScope 的 Qwen OpenAI 兼容接口接入，重排使用 DashScope 原生 Rerank 协议。

## 模块结构

```text
team-mind-web            Controller、启动类、应用配置、Swagger 配置
team-mind-ai             Qwen 对话/Embedding 网关、RAG 问答编排、提示词
team-mind-knowledge      知识库、文档、分块、解析器、向量库、BM25、RRF、重排
team-mind-observability  轻量 RAG Trace、Evidence Board、Qdrant 健康检查
team-mind-common         统一响应、错误码、业务异常
team-mind-system         用户与系统模块（Phase 1 骨架）
team-mind-evaluation     评测模块（Phase 1 骨架）
team-mind-ui             Vue 3 + TypeScript 前端工作区（Phase 8 实现，非 Maven 模块）
```

## 环境要求

- JDK 21（Maven Enforcer 要求 `[21,22)`，JDK 17 或 22+ 会直接构建失败）
- Maven 3.9+，项目自带 `mvnw` / `mvnw.cmd`
- Docker Desktop 或 Docker Engine + Docker Compose
- 一个 DashScope API Key（云端 Qwen 与 Reranker 需要）
- 可选：本地 Ollama（完全离线运行对话与 Embedding，但默认重排仍需 DashScope 或兼容的 Rerank 服务）

## 快速开始

### 第一步：启动基础设施

在项目根目录执行：

```bash
cp .env.example .env
docker compose up -d --wait
```

这会启动 PostgreSQL（默认 `5432`）、Redis（`6379`）和 Qdrant（HTTP `6333`、gRPC `6334`）。`--wait` 会等待三个容器的健康检查通过。

`.env` 只用于 Docker Compose 的变量替换，**Java 进程不会自动读取它**。如果修改了 `.env` 中的 `POSTGRES_PORT`，还需要给 Java 进程设置对应的 `DB_PORT`，两者名称不同但必须指向同一个端口。

查看状态或停止：

```bash
docker compose ps
docker compose down          # 停止容器，保留数据
docker compose down --volumes # 连同数据一起删除，谨慎使用
```

### 第二步：选择一种启动方式

#### 方式一：打包后启动（接近生产部署）

```bash
export QWEN_API_KEY="你的 DashScope API Key"
./mvnw -pl team-mind-web -am package -DskipTests
java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local
```

启动成功后访问 <http://localhost:8080/api/v1/swagger-ui.html>。应用缺少密钥时仍能启动，但文档向量化和问答会返回 `50301` 依赖服务不可用。

也可以把变量写成一行，避免污染当前终端：

```bash
QWEN_API_KEY="sk-xxx" java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local
```

Windows PowerShell：

```powershell
$env:QWEN_API_KEY="你的 DashScope API Key"
.\mvnw.cmd -pl team-mind-web -am package -DskipTests
java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar --spring.profiles.active=local
```

`local` profile 会创建一个 ID 为 `1` 的本地占位用户 `local-developer`，只用于满足知识库外键和开发期调用。该用户没有可用密码，不会在测试或生产环境创建。

#### 方式二：在 IntelliJ IDEA 中直接启动（适合开发调试）

1. **打开项目**：选择项目根目录的 `pom.xml`，以 Maven 项目导入，等待依赖下载和索引完成。
2. **设置 JDK**：`File → Project Structure → Project`，将 Project SDK 和 Language level 设为 JDK 21；再到 `Settings → Build, Execution, Deployment → Build Tools → Maven → Runner`，把 JRE 设为 JDK 21。不要在 JDK 17/22 下运行，Enforcer 会拦截。
3. **新建运行配置**：`Run → Edit Configurations → + → Spring Boot`（没有该类型时选择 `Application`）。
4. **填写配置**：

   | 配置项 | 值 |
   | --- | --- |
   | Name | `TeamMind-local` |
   | Module / Use classpath of module | `team-mind-web` |
   | Main class | `com.teammind.web.TeamMindApplication` |
   | Program arguments | `--spring.profiles.active=local` |
   | Working directory | 项目根目录 `TeamMind`（保证 `./data/documents` 落在项目内） |
   | Environment variables | `QWEN_API_KEY=你的密钥` |

5. **可选变量**：需要单独配置重排密钥时追加 `RERANK_API_KEY=...`；使用本地 Ollama 时把 Program arguments 改为 `--spring.profiles.active=local,ollama`，无需 `QWEN_API_KEY`。
6. **运行**：点击 Run，控制台出现 `Started TeamMindApplication` 后即可访问 Swagger。

IDEA 的环境变量填写位置：`Run → Edit Configurations → 选中配置 → Environment variables` 右侧的编辑按钮，格式为 `KEY=VALUE`，多个变量用 `;` 分隔。这些值保存在 `.idea/workspace.xml`，该目录已被 `.gitignore` 忽略；团队共享机器上建议安装 **EnvFile** 插件，直接加载 `.env`，避免把密钥写进 IDE 配置。

macOS 从 Dock 启动的 IDEA 不会继承终端 `~/.zshrc` 中的 `export`，因此**在终端里 export 后再启动打包方式有效，但对 IDEA 图形界面无效**，必须在运行配置里单独填写，或改用 EnvFile 插件。

### 使用本地 Ollama 模型（可选）

默认推荐 `qwen3.5:9b` 负责回答、`qwen3-embedding:0.6b` 负责 1024 维向量：

```bash
ollama pull qwen3-embedding:0.6b
ollama pull qwen3.5:9b
./mvnw -pl team-mind-web -am package -DskipTests
java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local,ollama
```

本地模式使用独立 Qdrant 集合 `team_mind_chunks_local_qwen3_1024`，不能复用云端 Embedding 生成的旧集合；已有文档需要重新上传或逐篇调用重处理接口来重建本地向量。完整部署、显存检查和接口自检步骤见 [本地 Ollama 部署说明](docs/local-models.md)。

## 接口清单

Swagger UI：<http://localhost:8080/api/v1/swagger-ui.html>

OpenAPI JSON：<http://localhost:8080/api/v1/api-docs>

所有业务接口统一返回 `code`、`message`、`data`、`path`、`timestamp`、`errors`，其中值为 `null` 的字段会被省略：

```json
{
  "code": "0",
  "message": "请求成功",
  "data": {},
  "timestamp": "2026-01-01T00:00:00Z"
}
```

### 知识库管理

| 方法 | 路径 | 作用 | 主要参数 |
| --- | --- | --- | --- |
| POST | `/api/v1/knowledge-bases` | 创建知识库 | Body：`name`、`description`、`ownerId` |
| GET | `/api/v1/knowledge-bases` | 查询当前用户的知识库列表 | Query：`ownerId` |
| GET | `/api/v1/knowledge-bases/{id}` | 查询知识库详情 | Path：`id`；Query：`ownerId` |
| PUT | `/api/v1/knowledge-bases/{id}` | 修改名称和描述 | Path：`id`；Body：`name`、`description`、`ownerId` |
| DELETE | `/api/v1/knowledge-bases/{id}` | 删除知识库，库内仍有文档时拒绝 | Path：`id`；Query：`ownerId` |

### 文档管理

| 方法 | 路径 | 作用 | 主要参数 |
| --- | --- | --- | --- |
| POST | `/api/v1/documents/upload` | 上传文档并异步处理，立即返回 `PENDING` | Form：`knowledgeBaseId`、`ownerId`、`file` |
| GET | `/api/v1/documents` | 查询知识库下的文档列表 | Query：`knowledgeBaseId`、`ownerId` |
| GET | `/api/v1/documents/{id}` | 查询文档详情 | Path：`id`；Query：`ownerId` |
| GET | `/api/v1/documents/{id}/status` | 轮询处理状态 | Path：`id`；Query：`ownerId` |
| POST | `/api/v1/documents/{id}/reprocess` | 重新解析、分块、向量化并补齐 BM25 词元 | Path：`id`；Query：`ownerId` |
| DELETE | `/api/v1/documents/{id}` | 删除文档并清理分块与向量 | Path：`id`；Query：`ownerId` |

### 检索、问答与证据

| 方法 | 路径 | 作用 | 主要参数 |
| --- | --- | --- | --- |
| POST | `/api/v1/retrieval/search` | 按指定策略独立检索，不调用对话模型 | Body：`query`、`knowledgeBaseIds`、`ownerId`、`topK`、`strategy` |
| POST | `/api/v1/chat/completions` | 知识库问答，返回答案、真实引用、`traceId` 和策略 | Body：`message`、`knowledgeBaseIds`、`ownerId`、`topK`、`strategy` |
| GET | `/api/v1/evidence/{traceId}` | 查询一次请求的 Evidence 证据快照 | Path：`traceId`；Query：`ownerId` |

### 健康检查

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/actuator/health` | 总体健康状态 |
| GET | `/api/v1/actuator/health/liveness` | 存活探针 |
| GET | `/api/v1/actuator/health/readiness` | 就绪探针，检查数据库、Redis、Qdrant |

### 业务错误码

| code | HTTP 状态 | 含义 |
| --- | --- | --- |
| `0` | 200 | 成功 |
| `40001` | 400 | 请求参数校验失败 |
| `40002` | 400 | 请求体格式不正确 |
| `40401` | 404 | 资源不存在或不属于当前用户 |
| `40501` | 405 | 请求方法不支持 |
| `40901` | 409 | 数据冲突，如知识库名称重复 |
| `41301` | 413 | 上传文件超过大小限制 |
| `41501` | 415 | 不支持的媒体类型 |
| `42201` | 422 | 业务规则校验失败 |
| `50000` | 500 | 服务器内部错误 |
| `50301` | 503 | 依赖服务不可用，如缺少模型密钥或 Qdrant 异常 |

## 使用示例

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

上传接口只负责保存文件和调度异步处理，立即返回 `PENDING`。轮询状态：

```bash
curl "http://localhost:8080/api/v1/documents/文档ID/status?ownerId=1"
```

状态变为 `COMPLETED` 后即可问答：

```bash
curl -X POST http://localhost:8080/api/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"message":"订单模块最大连接数是多少？","knowledgeBaseIds":[知识库ID],"ownerId":1,"topK":5}'
```

回答包含 `citations`、`traceId` 和 `strategy`。默认策略为 `HYBRID_RERANK`，需要 `QWEN_API_KEY`（或单独的 `RERANK_API_KEY`）。可通过请求中的 `strategy` 改为 `DENSE`、`BM25` 或 `HYBRID`，也可独立调用策略检索：

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

## 配置说明

### 配置文件与优先级

| 文件 | 作用 |
| --- | --- |
| `team-mind-web/src/main/resources/application.yml` | 公共默认配置，所有 profile 生效 |
| `application-local.yml` | 本地开发 profile，仅打开健康检查详情 |
| `application-ollama.yml` | 本地 Ollama 覆盖 Chat 与 Embedding 配置 |
| `.env` / `.env.example` | 仅供 Docker Compose 使用，Java 进程不读取 |
| `.env` 之外的环境变量 | 覆盖 `application.yml` 中的占位值，优先级更高 |

Spring Boot 的覆盖顺序为：命令行参数 > 环境变量 > 激活 profile 的配置 > `application.yml`。因此不需要为了改密钥去修改任何 `application*.yml`。

### 环境变量

**模型与敏感信息**（建议只通过环境变量或 IDEA 运行配置注入）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `QWEN_API_KEY` | 空 | DashScope 密钥；缺失时向量化和问答返回 `50301` |
| `QWEN_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI 兼容接口地址 |
| `QWEN_EMBEDDING_MODEL` | `text-embedding-v4` | Embedding 模型 |
| `QWEN_CHAT_MODEL` | `qwen-plus` | 对话模型 |
| `EMBEDDING_DIMENSION` | `1024` | 向量维度，必须与集合维度一致 |
| `RERANK_API_KEY` | 复用 `QWEN_API_KEY` | 独立重排密钥 |
| `RERANK_BASE_URL` | `https://dashscope.aliyuncs.com/api/v1` | 重排服务地址 |
| `RERANK_MODEL` | `qwen3.7-text-rerank` | 重排模型 |

**基础设施**（本地 Docker 默认值即可）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | PostgreSQL 地址 |
| `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | `teammind` / `teammind` / `teammind_local_password` | 数据库连接 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / 空 | Redis 连接 |
| `QDRANT_HOST` / `QDRANT_GRPC_PORT` | `localhost` / `6334` | Qdrant gRPC 连接 |
| `QDRANT_API_KEY` / `QDRANT_USE_TLS` | 空 / `false` | Qdrant 鉴权与 TLS |
| `SERVER_PORT` | `8080` | HTTP 端口 |

**文档与检索**：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DOCUMENT_STORAGE_PATH` | `./data/documents` | 上传文件存储目录，相对启动工作目录 |
| `DOCUMENT_MAX_FILE_BYTES` | `20971520` | 单文件大小上限（20 MB） |
| `DOCUMENT_CHUNK_TOKENS` / `DOCUMENT_OVERLAP_TOKENS` | `640` / `100` | 分块长度与重叠 |
| `DOCUMENT_EMBEDDING_BATCH_SIZE` / `DOCUMENT_MAX_CHUNKS` | `10` / `2000` | Embedding 批大小与单文档最大分块数 |
| `QDRANT_COLLECTION` | `team_mind_chunks` | 云端模型的向量集合 |
| `RAG_TOP_K` / `RAG_SCORE_THRESHOLD` | `5` / `0.25` | 默认返回条数与最低分数 |
| `RAG_DENSE_CANDIDATE_SIZE` / `RAG_BM25_CANDIDATE_SIZE` / `RAG_RERANK_CANDIDATE_SIZE` | `20` / `20` / `20` | 各阶段候选数量 |
| `RAG_STRATEGY` | `HYBRID_RERANK` | 默认检索策略 |
| `RAG_RRF_K` / `RAG_MAX_TERM_COUNT` / `RAG_PARALLEL_TIMEOUT` | `60` / `64` / `15s` | RRF 常数、BM25 最大词元数、并行检索超时 |

**Ollama 模式**：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `OLLAMA_BASE_URL` | `http://localhost:11434/v1` | Ollama OpenAI 兼容地址 |
| `OLLAMA_API_KEY` | `ollama` | Ollama 不校验，但客户端要求非空 |
| `OLLAMA_EMBEDDING_MODEL` / `OLLAMA_CHAT_MODEL` | `qwen3-embedding:0.6b` / `qwen3.5:9b` | 本地模型 |
| `OLLAMA_QDRANT_COLLECTION` | `team_mind_chunks_local_qwen3_1024` | 本地独立集合 |
| `OLLAMA_READ_TIMEOUT` | `180s` | 本地模型首次加载较慢，放宽读取超时 |

### 敏感信息处理

- `.env`、`*.local`、`*.pem`、`*.key`、`.idea/`、`data/` 和构建产物都已被 `.gitignore` 忽略。
- 仓库只保留 `.env.example` 作为模板，里面没有真实密钥；不要把自己的 `.env` 或密钥写回模板。
- 密钥只放在三类位置：终端环境变量、IDEA 运行配置的 Environment variables、或生产环境的密钥管理服务。
- 不要把密钥写进 `application*.yml`、测试代码、README、Dockerfile 或前端代码；一旦提交，即使后续删除也会留在 Git 历史中。
- IDEA 的 Environment variables 保存在 `.idea/workspace.xml`，该文件不提交；多人共用电脑时建议使用 EnvFile 插件或系统级密钥管理。

## 数据库迁移

Flyway 在应用启动时自动执行 `team-mind-web/src/main/resources/db/migration` 下的迁移：

- `V1__create_phase1_schema.sql`：用户、知识库、文档、分块、Trace、Evidence 基础表
- `V2__add_phase2_constraints.sql`：Phase 2 约束与索引
- `V3__add_phase3_advanced_rag.sql`：BM25 检索词元、生成式 `tsvector`、GIN 索引，以及 Evidence 快照字段

已应用的迁移脚本不要修改，否则会触发 Flyway 校验失败；需要变更结构时新增 `V4__*.sql`。

## 构建与测试

```bash
./mvnw clean package        # 编译并运行单元测试
./mvnw test                 # 只运行单元测试
```

真实基础设施集成测试会使用独立端口和临时存储，结束后自动删除：

```bash
./scripts/verify-infrastructure.sh
```

该脚本启动 PostgreSQL `15432`、Redis `16379`、Qdrant `16333/16334`，运行基础设施连接、Flyway、HTTP 健康接口以及“PDF 上传 → 分块 → 向量入库 → BM25/RRF/重排 → 带引用回答 → Evidence → 删除清理”的 Phase 3 全链路测试。外部模型在集成测试中替换为本地确定性实现，不消耗 Qwen 配额。

## 已知限制

- 尚未接入身份认证，请求中的 `ownerId` 只是开发期归属参数，任意调用者都可以伪造，**不能视为安全认证**，当前版本只适合受控开发环境。
- 没有文件病毒扫描、接口限流、对象存储和模型计费监控；上传文件保存在本地磁盘。
- 只支持 PDF、DOCX、TXT 和 Markdown；图片 OCR、Excel、PPT 等格式尚未支持。
- Ollama profile 只覆盖对话与 Embedding，默认的 `HYBRID_RERANK` 仍需要 DashScope Reranker 或兼容接口；不配置时可改用 `HYBRID`。
- 没有 SSE 流式输出、Agent 工具调用、短期/长期记忆、完整 Span/Token 追踪和前端业务页面，这些属于后续阶段。

## 常见问题

**构建时报 Java 版本不满足 `[21,22)`**：切换项目 SDK 和 Maven Runner 的 JRE 到 JDK 21。IDEA 自带 JDK 可能不是 21，需要单独下载。

**`docker compose up -d --wait` 失败**：确认 Docker Desktop 已启动；检查 `5432`、`6379`、`6333`、`6334` 是否被其他程序占用，可在 `.env` 中修改端口。

**接口返回 `50301`**：优先检查 `QWEN_API_KEY` 是否注入到 Java 进程。用打包方式启动时确认 `export` 与 `java -jar` 在同一个终端；用 IDEA 时确认写在运行配置的 Environment variables 里，而不是只写在 `.env` 中。使用 Ollama 时还要确认 `ollama serve` 正在运行。

**应用启动了但文档一直 `PENDING`**：处理在事务提交后异步执行，查看应用日志中的异常；`FAILED` 时接口会返回 `failureReason`。

**旧文档 BM25 检索不到**：Phase 2 入库的文档缺少 `search_tokens`，调用 `POST /api/v1/documents/{id}/reprocess` 或重新上传。

**`8080` 端口被占用**：启动时加 `--server.port=8081`，或设置 `SERVER_PORT=8081`。

**Flyway 报 checksum mismatch**：说明已应用的迁移被修改。不要改历史脚本；开发环境可清空数据库后重启，其他环境使用 `flyway repair` 并评估影响。
