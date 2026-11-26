# TeamMind 当前架构说明（Phase 4）

## 目标与边界

Phase 4 在 Phase 3 高级 RAG 之上实现 Agent 系统：规则预处理、LLM 结构化任务理解、任务分类、Tool Registry、Function Calling、ReAct 执行循环、工具异常与超时处理、执行次数限制和 SSE 流式回答。

本阶段仍不包含长期记忆、完整 Span/Token 追踪、量化评测、登录认证和前端业务页面。`historyMemorySearch` 是明确返回“Phase 5 未启用”的占位工具，`getConversationHistory` 只读取本次请求携带的历史消息。`ownerId` 仍只是开发期归属参数，不是身份认证。

## 模块与依赖

```text
team-mind-web
  -> team-mind-ai
       -> team-mind-system
       -> team-mind-knowledge -> team-mind-common
       -> team-mind-observability -> team-mind-knowledge
  -> team-mind-evaluation -> team-mind-ai
```

`team-mind-ai` 新增 `agent` 包：

```text
agent/task      规则预处理、任务类型、结构化任务理解与校验
agent/tool      工具端口、注册表、参数校验、证据编号器、五个工具实现
agent/react     ReAct 循环、执行步骤、终止原因、Agent 响应
agent/model     Agent 模型端口与 Qwen OpenAI 兼容实现
agent/prompt    任务理解、ReAct、最终回答提示词
agent/config    Agent 执行边界配置与专用线程池
```

`agent` 只依赖 `KnowledgeSearchService`、`DocumentService`、`KnowledgeBaseService`、`EvidenceRecorder` 等应用端口，不直接访问 Mapper、Qdrant 或数据库。模型调用通过 `AgentModelGateway` 抽象，测试可替换为确定性实现。

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

检索工具通过 `EvidenceRecorder` 写入真实 Chunk 快照，回答完成后按实际引用编号更新 `cited=true`。Agent 复用 `ai_trace` 记录状态、问题、知识库范围、任务类型、回答和耗时；完整 Span/工具调用表留到 Phase 6。

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

## 已知限制

- 真实 Qwen Function Calling 和流式接口尚未用付费配额验收，自动化测试使用本地 HTTP 替身。
- 任务分类效果尚未用固定数据集量化，属于 Phase 7。
- `documentSearch` 因底层检索暂不支持按文档下推过滤，采用扩大候选集后精确过滤，可能遗漏候选集之外的文档分块。
- 长期记忆、会话持久化、完整执行 Span、Agent 监控页面和身份认证仍属于后续阶段。
- SSE 连接可能受反向代理空闲超时影响，生产部署需要配置代理缓冲和超时。
