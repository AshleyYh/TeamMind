# TeamMind 当前架构说明（Phase 3）

## 目标与边界

Phase 3 在 Phase 2 的基础上实现高级 RAG：BM25 关键词召回、Dense + BM25 并行检索、RRF 融合、BGE 系列 Reranker、有效版本过滤与 Evidence Board。支持单独执行四种检索策略，问答默认采用混合检索与重排。

本阶段不包含 Agent、记忆、完整 Span/Token 追踪、量化评测、SSE、登录认证和前端业务页面。`ownerId` 仍只是开发期归属参数。

## 模块与依赖

```text
team-mind-web
  -> team-mind-ai
       -> team-mind-system
       -> team-mind-knowledge -> team-mind-common
       -> team-mind-observability -> team-mind-knowledge
  -> team-mind-evaluation -> team-mind-ai
```

`knowledge` 负责文档、向量存储、关键词召回、版本过滤、融合和重排；`ai` 负责模型对话和问答编排；`observability` 负责轻量 RAG Trace 与 Evidence 持久化。`web` 只包含 Controller、启动类和应用配置。

`EmbeddingGateway`、`VectorStoreGateway`、`ChatCompletionGateway`、`RerankerGateway` 和 `EvidenceRecorder` 都是端口接口。知识检索通过证据端口写入记录，不依赖具体 Mapper；观测模块实现该端口，避免循环依赖。

## 文档入库链路

上传、解析、分块和异步状态沿用 Phase 2。新增步骤为：每个 Chunk 在入库前生成 `search_tokens`，PostgreSQL 同时保存原文与检索词元。英文和数字保留完整词元，代码标识符按非字母数字边界切分，中文生成连续二元词。该方案不依赖外部中文词典，后续可替换为领域词典。

Qdrant 保存向量，PostgreSQL 保存元数据、BM25 词元及由词元生成的 `tsvector`。升级前的旧文档必须重处理以补齐检索词元；现有向量无需强制重建。

## 高级检索链路

```text
用户问题
  -> 校验知识库归属与请求参数
  -> 按策略选择召回路径
       -> DENSE：Embedding -> Qdrant -> 有效 Chunk 加载
       -> BM25：分词 -> PostgreSQL BM25 -> 有效 Chunk 加载
       -> HYBRID：两路并行 -> RRF -> 有效版本过滤
       -> HYBRID_RERANK：混合结果 -> Reranker -> 最终 Top K
  -> 持久化 Evidence（traceId、原文、版本、排名、分数）
  -> 构建带编号的证据上下文
  -> Qwen Chat
  -> 校验引用范围并标记实际引用
  -> 返回答案、真实引用、traceId 和 strategy
```

BM25 使用 `k1=1.2`、`b=0.75` 与 Robertson IDF。文档频率和平均长度只基于所选知识库中的当前有效分块计算，减少跨知识库统计偏差。候选在 SQL 中限制知识库、文档状态和版本，加载器再次核验当前归属和版本，防止残留向量或并发更新产生过期证据。

RRF 公式为 `Σ 1 / (60 + rank)`，同一 Chunk 的两路排名与原始分数保留在 `RetrievedChunk` 和 Evidence 中；非混合策略不写 RRF 分数。重排使用 DashScope 原生文本 Rerank 协议，推荐 `qwen3.7-text-rerank`，模型只能返回候选索引和分数，不能修改证据。服务层统一丢弃重复、越界与非有限分数。

Dense 与 BM25 使用独立有界线程池并行运行并共享一个截止时间。调用超时或失败会显式失败，不静默降级为另一种策略。没有证据时直接返回，不调用对话模型。

## Evidence 与轻量 Trace

每次检索或问答返回 32 位十六进制 `traceId`。独立检索（包括无命中）会创建轻量 `ai_trace`，问答则在请求开始时创建并复用同一 ID。Evidence 保存最终 Top K 的原文、版本、页码、两路排名、融合/重排分数和重排标记；只有回答实际出现的 `[编号]` 才设置 `cited=true`。

Evidence 采用历史原文快照，不因文档重处理或删除而失效。迁移解除旧表对当前文档/Chunk 的删除外键，但创建证据仍必须来自服务端已校验的真实 Chunk。`ai_trace` 当前保存状态、问题、策略、回答和耗时，不提供完整执行节点，完整追踪属于 Phase 6。

## 配置、安全与限制

`RAG_DENSE_CANDIDATE_SIZE`、`RAG_BM25_CANDIDATE_SIZE`、`RAG_RERANK_CANDIDATE_SIZE`、`RAG_RRF_K`、`RAG_PARALLEL_TIMEOUT` 和 `RAG_STRATEGY` 控制检索；`RERANK_*` 控制重排接口，密钥默认复用 `QWEN_API_KEY`。所有外部模型调用具有连接和读取超时，异常转换为稳定的业务错误。

Ollama profile 只覆盖 Chat 与 Embedding；默认混合重排仍需要 DashScope 密钥，或配置兼容的 Reranker 服务。当前没有真实身份认证，也没有文件病毒扫描、限流、对象存储和模型计费监控。具体验证范围和待验收项见 `docs/phase3-verification.md`。
