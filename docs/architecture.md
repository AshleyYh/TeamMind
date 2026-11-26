# TeamMind 当前架构说明（Phase 8）

## 目标与边界

Phase 8 完成 Vue 3 + TypeScript + Element Plus 控制台和前后端联调：知识库、文档上传与状态、SSE 多轮对话、引用证据、Agent 执行步骤、Trace/Span 监控、长期记忆管理、评测中心和 Dashboard 统计。

本阶段仍不包含真实登录认证。前端登录页只保存开发期 `ownerId`，所有后端接口仍以 `ownerId` 做资源隔离。内置评测数据集是小规模示例，真实效果评测需要按企业知识库替换数据。

## 模块与依赖

```text
team-mind-web
  -> team-mind-ai
       -> team-mind-system
       -> team-mind-knowledge -> team-mind-common
       -> team-mind-observability -> team-mind-knowledge
       -> team-mind-memory -> team-mind-common
  -> team-mind-evaluation -> team-mind-ai
```

`team-mind-evaluation` 负责数据集、指标、评测编排和报告：

```text
evaluation/dataset   内置 JSON 数据集、白名单加载、版本和 SHA-256
evaluation/metric    Recall@K、MRR、NDCG@K
evaluation/application  任务/RAG/记忆评测服务、运行与用例持久化
evaluation/report    EvaluationReport 和 Markdown 渲染
evaluation/domain    EvaluationRun、EvaluationCase
evaluation/mapper    EvaluationRunMapper、EvaluationCaseMapper
```

`team-mind-memory` 负责会话元数据、Redis 消息读写、摘要存储、长期记忆实体与检索：

```text
memory/config       MemoryProperties：TTL、消息窗口、摘要阈值、记忆检索上限
memory/domain       Conversation、Memory、StoredMessage、ConversationSummary、枚举
memory/application  ConversationService、MemoryService
memory/mapper       ConversationMapper、MemoryMapper
```

`team-mind-observability` 负责 Trace/Span 持久化和查询：

```text
observability/domain       RagTrace、TraceSpan、Evidence
observability/application  RagTraceService、EvidenceService、RetrievalTraceRecorderAdapter
observability/mapper       RagTraceMapper、TraceSpanMapper、EvidenceMapper
```

`RagTraceService` 提供 Trace 创建/收尾、Span 开始/结束、一次性 Span 写入和 Trace/Span 查询。Span 写入失败开放，只记录日志，不影响业务回答。

`team-mind-ai` 的 `agent` 包负责任务理解、工具和 ReAct，并通过 `ConversationSummaryService`、`MemoryExtractionService` 使用记忆模块：

```text
agent/task      规则预处理、任务类型、结构化任务理解与校验
agent/tool      工具端口、注册表、参数校验、证据编号器、五个工具实现
agent/react     ReAct 循环、执行步骤、终止原因、Agent 响应
agent/memory    会话上下文加载、增量摘要、长期记忆提取
agent/model     Agent 模型端口与 Qwen OpenAI 兼容实现
agent/prompt    任务理解、ReAct、最终回答、摘要和记忆提取提示词
agent/config    Agent 执行边界配置与专用线程池
```

`agent` 只依赖应用服务端口，不直接访问 Mapper、Qdrant 或数据库。模型调用通过 `AgentModelGateway`/`ChatCompletionGateway` 抽象，测试可替换为确定性实现。

## 记忆系统

### 短期记忆

- 会话元数据保存在 PostgreSQL `ai_conversation`，消息正文保存在 Redis List。
- Redis key 为 `teammind:memory:conversation:{conversationId}:messages`，默认 7 天过期。
- 每次对话只把最近 `MEMORY_CONTEXT_MESSAGES` 条消息放入提示词。
- 消息数超过 `MEMORY_SUMMARY_THRESHOLD` 时，把超出窗口的旧消息与已有摘要合并，写入摘要 key，再裁剪 Redis，只保留最近窗口。
- 摘要失败时保留原始消息并记录警告，下次请求重试，不丢历史。
- Redis 不可用时，Agent 降级为请求携带的 `history`，会话查询接口返回 `50301`。

### 长期记忆

- 长期记忆保存在 PostgreSQL `ai_memory`，字段包括类型、标题、内容、摘要、来源会话、来源 Trace、版本、置信度、状态和使用时间。
- 新记忆先保存为 `DRAFT`；只有 `POST /api/v1/memories/{id}/confirm` 后才变为 `ACTIVE` 并参与检索。
- 修改内容递增 `version`，失效和逻辑删除保留来源数据用于追溯。
- 检索只返回当前用户的 `ACTIVE` 且未删除记忆，使用关键词和中文二元词 OR 匹配，返回后更新 `lastUsedAt`。
- 从会话提取长期记忆使用 LLM 输出 JSON 候选，服务端校验类型、内容和置信度，跳过敏感/非法候选；提取结果仍是 DRAFT，需要人工确认。

### 会话与 Agent 集成

```text
Agent 请求（conversationId）
  -> 校验/创建会话元数据（ai_conversation）
  -> 从 Redis 加载最近消息 + 增量摘要
  -> 摘要 + 最近消息进入任务理解和 Agent 上下文
  -> ReAct 执行，historyMemorySearch 可检索 ACTIVE 长期记忆
  -> 保存 user/assistant 消息回 Redis
  -> ai_trace 关联 conversation_id
```

`historyMemorySearch` 的 `记忆#n` 不是文档引用编号；只有 `knowledgeSearch` 等工具返回的 `证据 [n]` 才能作为最终答案的文档引用。

## 可观测性

### Trace 与 Span

- `ai_trace` 保存一次请求的 traceId、用户、会话、状态、策略、问题、回答、总耗时、输入/输出/总 Token、错误信息和结束时间。
- `ai_trace_span` 保存执行节点：`spanId`、`parentSpanId`、`spanType`、状态、耗时、输入/输出 Token、错误类型、错误信息、输入/输出摘要和创建时间。
- 父节点通过 `parentSpanId` 关联，例如 `AGENT_DECISION` 下的 `TOOL_CALL`。`RAG_RETRIEVAL` 由知识模块通过端口写入，目前作为根节点，不与 `TOOL_CALL` 嵌套。

### 记录节点

| Span 类型 | 来源 | 内容 |
| --- | --- | --- |
| `MEMORY_CONTEXT` | Agent | 会话消息、增量摘要、摘要模型 Token |
| `TASK_UNDERSTANDING` | Agent | 任务分类、置信度、任务理解 Token |
| `AGENT_DECISION` | Agent | 每轮 Function Calling 决策、工具数量、模型 Token |
| `TOOL_CALL` | Agent | 工具名、参数摘要、结果摘要、失败类型和耗时 |
| `RAG_RETRIEVAL` | 知识模块 | 查询、策略、结果数、检索耗时和失败信息 |
| `LLM_GENERATION` | 直接 RAG | 生成耗时、输入/输出 Token |
| `FINAL_ANSWER` | Agent | 最终答案长度、是否降级、生成 Token |

### Token 与耗时

- Qwen Chat 网关解析 OpenAI 兼容响应的 `usage.prompt_tokens` / `usage.completion_tokens`。
- Agent 汇总记忆摘要、任务理解、每轮决策和最终答案的 Token，写入 `ai_trace`。
- 每个模型 Span 记录自己的输入/输出 Token；每个业务 Span 记录自己的耗时。
- 失败请求保留已消耗 Token 和错误信息；首次模型调用失败也会写入失败 Trace。

### 查询 API

```text
GET /api/v1/traces?ownerId=1&limit=20
GET /api/v1/traces/{traceId}?ownerId=1
GET /api/v1/traces/{traceId}/spans?ownerId=1
GET /api/v1/traces/{traceId}/evidences?ownerId=1
```

Trace 详情返回主记录和平铺 Span；客户端可按 `parentSpanId` 构建时间线。查询前先校验 Trace 归属，避免跨用户读取。

## Agent 处理链路

```text
用户问题
  -> 空输入/长度/控制字符清洗
  -> 固定意图与显式知识库范围识别（规则层）
  -> LLM JSON 结构化任务理解（TaskUnderstanding）
  -> 校验任务类型、知识库范围、工具名、时间范围和置信度
       -> 模型不可用或 JSON 非法时降级为规则分类，并记录 warnings
  -> ReAct 循环
       -> Qwen Function Calling 选择工具
       -> ToolRegistry 按名称白名单执行
       -> 工具参数、知识库/文档范围二次校验
       -> Observation 回填模型，继续决策或结束
  -> 最终答案（非流式或 SSE 流式）
  -> 引用编号过滤、Evidence 标记、轻量 ai_trace 收尾
```

## 规则预处理与任务理解

规则预处理只承担确定性工作：空输入、长度上限、控制字符清洗、固定问候/历史/文档检索意图，以及“知识库编号: 1”“第 2 个知识库”等显式范围解析。规则不会猜测复杂语义。

任务理解使用 Qwen JSON 模式，要求模型输出 `taskType`、`userIntent`、`entities`、`keywords`、`knowledgeBaseIds`、`timeRange`、`requiredTools`、`confidence`。服务端逐项校验：

- `taskType` 必须属于 `KNOWLEDGE_QA`、`HISTORY_QUERY`、`DOCUMENT_SEARCH`、`GENERAL_CHAT`、`UNKNOWN`。
- `knowledgeBaseIds` 必须是请求允许范围与模型结果的交集，越界编号被丢弃并记录警告。
- `requiredTools` 必须是注册表中真实存在的工具，否则忽略并按任务类型使用默认工具。
- `timeRange` 必须是合法的 `yyyy-MM-dd`，且开始日期不能晚于结束日期。
- `confidence` 必须在 `0..1`，低于 `0.35` 时归类为 `UNKNOWN`。
- 模型返回非法 JSON、超时或不可用时，使用关键词规则降级，`ruleBased=true` 并写入警告。

固定问候、明确历史查询和明确文档检索会跳过模型任务理解，减少一次付费调用。

## 工具注册与权限边界

| 工具 | 作用 | 边界 |
| --- | --- | --- |
| `knowledgeSearch` | 复用 Phase 3 混合检索与重排 | 只能使用请求允许的知识库，可进一步收窄 |
| `documentSearch` | 指定文档范围检索 | 先校验文档归属，再按文档 ID 过滤候选 |
| `getDocumentDetail` | 文档名称、版本、状态等元信息 | 不返回服务器文件路径 |
| `historyMemorySearch` | 长期记忆查询占位 | 明确返回 Phase 5 未启用，不伪造历史 |
| `getConversationHistory` | 本次请求的最近消息 | 只接受 user/assistant，system 角色被丢弃 |

`ToolRegistry` 在启动时校验工具名唯一、描述非空、参数是 JSON Schema 对象。运行时只允许调用已注册工具，未知工具、非法 JSON、参数类型/范围错误、业务异常和未预期异常都转换为失败 Observation，不向客户端泄漏堆栈、SQL 或内部地址。

工具参数使用白名单，拒绝未知字段。模型可以收窄知识库/文档范围，但不能扩大请求范围。文档和知识库归属由现有应用服务再次校验。

## ReAct 循环与终止条件

每次模型调用都携带工具 JSON Schema，模型通过原生 Function Calling 发起一个或多个工具调用。工具结果按 OpenAI 协议回填 `role=tool` 消息，模型根据 Observation 决定下一步。

终止条件：

- 模型不再发起工具调用：使用模型给出的最终答案。
- 达到 `AGENT_MAX_ITERATIONS`（默认 5）：改为最终回答阶段。
- 达到 `AGENT_MAX_TOOL_CALLS`（默认 8）：未执行的调用回填“未执行”消息，改为最终回答。
- 整体 `AGENT_EXECUTION_TIMEOUT`（默认 90 秒）：停止发起模型或工具调用，使用确定性降级回答。
- 单工具 `AGENT_TOOL_TIMEOUT`（默认 20 秒）：取消 Future，记录失败 Observation。
- 连续工具失败达到 `AGENT_MAX_CONSECUTIVE_FAILURES`（默认 3）：停止工具调用。
- 首次模型调用即失败：直接返回 `50301`；已有观察时则返回降级答案并标记 `MODEL_ERROR`。

所有异常和终止原因都会写入响应 `termination` 和 `warnings`，不会静默截断。

## 引用与 Evidence

多个检索工具可能返回同一 Chunk。`EvidenceCollector` 按 Chunk ID 去重并分配全局编号，最终答案中的 `[n]` 与 `citations` 一一对应。

非流式回答和流式回答都经过 `CitationStreamSanitizer`：

- 合法编号保留，并记录为已引用。
- 越界、零或溢出的编号直接移除，并在 `warnings` 中记录数量。
- 非数字方括号（如 `[附录A]`）按普通文本保留。
- 跨增量拆分的 `[1]` 会被正确拼接。
- 流式模式下无法撤回已发送文本，因此采用“移除非法标记”而不是整段拒绝；普通 RAG 接口仍保持严格拒绝语义。

检索工具通过 `EvidenceRecorder` 写入真实 Chunk 快照，回答完成后按实际引用编号更新 `cited=true`。Agent 复用 `ai_trace` 记录状态、问题、知识库范围、任务类型、回答、耗时和 `conversation_id`；完整 Span/工具调用表留到 Phase 6。

## SSE 事件

`POST /api/v1/chat/stream` 返回 `text/event-stream`：

| 事件 | 内容 |
| --- | --- |
| `start` | `traceId` |
| `task` | 结构化任务理解结果 |
| `step` | `THOUGHT`、`ACTION`、`OBSERVATION` 执行步骤 |
| `answer_delta` | 最终答案增量文本 |
| `done` | 完整 `AgentAnswer`，含引用、终止原因、警告和 Token 用量 |
| `error` | 错误码、消息和已知的 `traceId` |

ReAct 决策与工具调用使用非流式模型调用，保证工具协议完整；最终回答使用流式模型调用。客户端断开后服务端停止继续发送事件。

## 配置

`AGENT_MAX_ITERATIONS`、`AGENT_MAX_TOOL_CALLS`、`AGENT_MAX_CONSECUTIVE_FAILURES`、`AGENT_MAX_EVIDENCE`、`AGENT_MAX_HISTORY_MESSAGES`、`AGENT_MAX_QUESTION_LENGTH`、`AGENT_MAX_OBSERVATION_CHARS`、`AGENT_MAX_CHUNK_CHARS`、`AGENT_EXECUTION_TIMEOUT` 和 `AGENT_TOOL_TIMEOUT` 控制执行边界。Agent 使用独立的 `agentToolExecutor` 有界线程池和 `agentStreamExecutor` 虚拟线程池，避免慢工具拖垮普通 RAG 检索线程池。

`MEMORY_CONVERSATION_TTL`、`MEMORY_MAX_MESSAGES`、`MEMORY_CONTEXT_MESSAGES`、`MEMORY_SUMMARY_THRESHOLD`、`MEMORY_MAX_SUMMARY_CHARS`、`MEMORY_MAX_SEARCH_RESULTS` 和 `MEMORY_MAX_EXTRACTION_CANDIDATES` 控制短期记忆窗口、摘要和长期记忆提取/检索上限。

## 评测体系

### 数据集

- `task-classification.json`：问题、期望任务类型和期望工具。
- `rag-retrieval.json`：查询、相关文档名、相关关键词和答案关键词。
- `memory-retrieval.json`：查询和期望记忆关键词。
- 数据集名使用白名单，加载时计算 SHA-256；运行记录保存名称、版本和 hash，可重复追溯。
- 单数据集最多 200 个用例。

### 指标

- `Recall@K`：前 K 个结果命中的相关项数 / 相关项总数。
- `MRR`：第一个相关结果排名的倒数。
- `NDCG@K`：二值相关性 DCG / IDCG。
- 任务准确率：任务类型分类正确率。
- 工具命中率：期望工具集合是否被实际建议工具覆盖。
- 引用精确率：实际引用文档中相关文档占比。
- 引用召回率：相关文档中被实际引用的占比。
- 答案关键词覆盖率：期望关键词在答案中出现的比例。

RAG 检索按文档名去重计算文档级指标；记忆评测按期望关键词判断记忆是否命中。答案/引用指标仅在 `evaluateAnswers=true` 时调用对话模型生成答案，默认关闭以避免无意义的模型消耗。

### 评测运行

```text
POST /api/v1/evaluation/tasks
POST /api/v1/evaluation/rag
POST /api/v1/evaluation/memories
GET  /api/v1/evaluation/datasets
GET  /api/v1/evaluation/runs
GET  /api/v1/evaluation/runs/{runId}
GET  /api/v1/evaluation/runs/{runId}/report
```

每次运行保存 `ai_evaluation_run`（状态、数据集 hash、策略、聚合指标、报告、错误）和 `ai_evaluation_case`（期望、实际、指标、耗时、错误）。运行 ID 为 32 位十六进制，报告以 Markdown 返回。评测运行同步执行，适合受控环境的小规模对比。

## 前端控制台

```text
team-mind-ui/
  src/api/          Axios 客户端、API 类型、POST SSE 解析
  src/composables/  localStorage 开发会话
  src/layouts/      侧边栏 + 顶栏控制台布局
  src/views/        工作台、知识库、文档、对话、Trace、记忆、评测、登录
  src/styles/       主题、Markdown、聊天、Trace、评测样式
  src/router/       路由和开发模式守卫
```

- 开发服务器 `5173` 把 `/api` 代理到后端 `8080`。
- SSE 使用 `fetch` + `ReadableStream` 手动解析，因为 `EventSource` 不支持 POST。
- 对话页消费 `start/task/step/answer_delta/done/error` 事件，渲染 Markdown、代码块、引用证据、执行步骤和 Token。
- Trace 页展示 Span、耗时、输入/输出 Token、错误节点和 Evidence。
- 评测页支持任务识别、RAG 多策略和记忆评测，并可查看历史运行与 Markdown 报告。
- 登录页是开发模式占位，没有密码、Token 或服务端会话。

## 已知限制

- 真实 Qwen Function Calling、流式接口、摘要和记忆提取尚未用付费配额验收，自动化测试使用本地 HTTP 替身。
- 任务分类、记忆提取和摘要效果尚未用固定数据集量化，属于 Phase 7。
- `documentSearch` 因底层检索暂不支持按文档下推过滤，采用扩大候选集后精确过滤，可能遗漏候选集之外的文档分块。
- 长期记忆检索使用关键词和中文二元词，没有向量召回；大记忆量场景需要后续增加 Embedding 或全文索引。
- 长期记忆提取需要显式调用接口并人工确认，尚未实现自动提取和前端审批页面。
- Trace/Span 已落库并提供查询 API；前端时间线、Agent 监控页面和身份认证属于 Phase 8。
- Span 只保存摘要，完整提示词和文档正文不会写入 Trace 表；Span 写入失败开放，可能缺少部分节点。
- 记忆提取和摘要的 Token 用量计入 Agent Trace；单独调用记忆提取接口目前不创建独立 Trace。
- 内置评测数据集是小规模示例，必须按真实知识库调整相关文档名和关键词，不能直接当作生产效果结论。
- RAG 评测默认不调用对话模型；答案/引用质量评测需要显式开启并消耗配额。
- 评测同步执行，大数据集可能长时间占用请求；没有统计显著性、置信区间和前端评测页面。
- 记忆评测基于当前用户已有 ACTIVE 记忆，会更新命中记忆的 `lastUsedAt`，没有独立评测沙箱。
- 前端没有单元测试和端到端自动化测试，当前通过 `vue-tsc` 和 `vite build` 验证；真实联调需要完整基础设施。
- Element Plus 全量引入使主包较大；后续可使用按需引入和 manualChunks 优化。
- 前端登录页只是开发模式 `ownerId` 入口，没有真实认证和权限控制。
- SSE 连接可能受反向代理空闲超时影响，生产部署需要配置代理缓冲和超时。
