# Phase 3 验收记录

## 已实现范围

Phase 3 实现了 BM25 关键词召回、Dense 与 BM25 并行检索、RRF 融合、DashScope `qwen3.7-text-rerank` 重排、有效文档版本过滤和 Evidence Board。检索支持 `DENSE`、`BM25`、`HYBRID`、`HYBRID_RERANK` 四种策略，每个结果都保留两路原始名次、原始分数、RRF 分数和重排分数。

中文、英文与代码标识符使用统一的轻量分词器，中文生成连续二元词，英文、数字和代码标识符保留完整词元。PostgreSQL 保存分词结果并计算 BM25，采用 `k1=1.2`、`b=0.75` 和 Robertson IDF。检索词元经过应用清洗后以参数绑定方式传入，不能直接注入 SQL 或全文检索运算符。

Dense 与 BM25 在独立有界执行器中并行执行，共享同一个 15 秒截止时间，任一路失败都会返回明确错误。RRF 默认平滑参数为 60，只合并排名，不直接相加异构分数；非混合策略的 `rrfScore` 为 `null`，避免误读为“融合得分为 0”。重排只接受模型返回的候选索引及相关性分数，重复、越界和非有限值在服务层统一丢弃，证据原文、文档和引用编号均由服务端决定。

Evidence 在最终 Top K 检索结果产生时持久化，保存原文、页码、版本、两路排名、融合/重排分数和 `reranked` 状态。回答中出现的引用编号会更新为 `cited=true`。历史证据采用不可变原文快照，不阻止文档重处理或删除。`ai_trace` 保存 Phase 3 轻量检索状态、问题、策略、回答和耗时，但不包含完整 Span 与 Token 统计，这些属于 Phase 6。

## 新增与扩展接口

`POST /api/v1/retrieval/search` 用于独立策略检索和排名比较，请求包含 `query`、`knowledgeBaseIds`、`ownerId`、可选 `topK` 及必填 `strategy`。`GET /api/v1/evidence/{traceId}?ownerId=1` 按归属查询证据。

`POST /api/v1/chat/completions` 新增可选 `strategy`，默认使用 `HYBRID_RERANK`；响应保留原 `answer` 和 `citations`，增加 `traceId` 和 `strategy`。无证据时仍不调用对话模型，越界引用仍会被拒绝。

## 数据库变更

新增 Flyway 迁移 `V3__add_phase3_advanced_rag.sql`，没有修改已应用的 V1/V2。`knowledge_chunk` 新增 `search_tokens`、生成式 `search_vector` 与 GIN 索引；`ai_trace` 新增知识库范围、问题、策略和回答；`ai_evidence` 新增归属、原文快照、页码、两路排名、RRF、重排标记与引用状态。升级前已有的 Evidence 会从 Trace、文档和 Chunk 回填可恢复字段。

Evidence 原文快照解除了对当前文档和 Chunk 的删除外键，以支持历史追溯和文档清理。服务端仍只根据当前有效 Chunk 创建证据，并通过 `owner_id` 过滤查询。文档删除使用 MyBatis-Plus `deleteById` 写入逻辑删除标记，避免 `updateById` 忽略 `@TableLogic` 字段。

Phase 2 已入库文档的 `search_tokens` 为空，升级后必须调用文档重处理接口或重新上传，才能进入 BM25 召回。Dense 旧数据仍可检索。

## 自动化验证结果

2026-11-17 使用临时隔离的 OpenJDK 21.0.13 与项目 Maven Wrapper 3.9.11，在不含历史构建产物的源码副本中执行：

```bash
./mvnw clean package
```

结果为成功，7 个 Maven 模块全部打包成功。默认单元与上下文测试共 **39 项，全部通过**。覆盖中文与代码分词、RRF 合并及保留原始排名、策略选择、重排边界、DashScope Reranker HTTP 协议与密钥校验、有效版本与知识库过滤、Evidence 快照和引用标记、逻辑删除、问答无证据及非法引用、原有 Spring 上下文和 MVC 参数校验。

真实基础设施验证使用本机临时启动的 PostgreSQL 14.24、Redis 6.0.16 和 Qdrant 1.16.0，执行：

```bash
./mvnw -pl team-mind-web -am test -Dgroups=integration -Dsurefire.excludedGroups=
```

5 项 integration 测试通过，包含默认测试后的总计为 **44 项、0 失败**。实际覆盖 Flyway V1/V2/V3、基础表与索引、PostgreSQL/Redis/Qdrant 健康、PDF 上传解析分块、Dense/BM25/HYBRID/HYBRID_RERANK、RRF 排名、Evidence 持久化与引用、文档和知识库删除清理。外部 Qwen 与 Reranker 仍由确定性测试实现替换，不消耗付费配额。

Reranker 单测使用本机临时 HTTP 服务验证真实请求和响应格式；这不代表真实 DashScope 模型效果验收。

## 本机验收步骤

启动 PostgreSQL、Redis 和 Qdrant，并配置 `QWEN_API_KEY` 后启动应用。默认重排复用该密钥，也可以用 `RERANK_API_KEY` 单独配置。Phase 2 文档先重处理至 `COMPLETED`。

```bash
curl -X POST http://localhost:8080/api/v1/retrieval/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"HikariCP 最大连接数","knowledgeBaseIds":[1],"ownerId":1,"topK":5,"strategy":"BM25"}'
```

将 `strategy` 分别改为 `DENSE`、`HYBRID` 和 `HYBRID_RERANK`，对比结果顺序、`denseRank`、`bm25Rank`、`rrfScore` 和 `rerankScore`。随后使用同一问题调用问答接口，记录响应 `traceId`，查询：

```bash
curl 'http://localhost:8080/api/v1/evidence/替换为traceId?ownerId=1'
```

核对文档名称、版本、页码、原文和 Chunk ID，确认回答出现的编号对应 `cited=true`。对同名文档上传新版本并重处理后，所有策略均不应返回旧版本。

在项目根目录、Docker 可用的本机执行 `./scripts/verify-infrastructure.sh` 可运行真实 PostgreSQL/Qdrant 集成链路，包括 BM25、RRF、重排结果结构、引用与证据关联以及删除清理；仅外部模型替换为确定性实现。

## 尚待真实环境验收与已知限制

真实 DashScope Reranker、Qwen Chat/Embedding 尚未调用，因此“模型效果提升”不能由上述自动化测试推断，需要固定数据集和 Phase 7 评测体系验证。PostgreSQL 验证环境为 14.24，生产基线是 16；已使用的生成列、数组、GIN 和 BM25 SQL 均为两者支持的语法，上线前仍应在 PostgreSQL 16 再执行一次。

BM25 为保证精确文档频率和平均长度，会按所选知识库统计当前有效语料；大知识库仍需用真实数据执行 `EXPLAIN (ANALYZE, BUFFERS)` 验证延迟，必要时预计算语料统计。中文单字查询不会命中只保存二元词的文档，英文暂未做词干还原，重排候选也受模型单次输入长度和配额限制。

默认 Ollama profile 只提供对话和 Embedding，不提供 Reranker；默认问答仍需 DashScope Reranker 密钥，或者配置兼容接口。`ownerId` 仍不是身份认证，任意调用者可伪造归属，因此当前版本只能用于受控开发环境。完整 Trace/Span、Agent、记忆、前端展示和量化检索评测仍分别属于后续阶段。
