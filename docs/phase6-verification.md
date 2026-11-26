# Phase 6 验收记录

## 已实现范围

Phase 6 在 Agent、RAG 和记忆链路之上补齐可观测性：

- 每个请求唯一 `traceId`。
- `ai_trace` 保存状态、问题、会话、策略、耗时、输入/输出/总 Token、回答、错误信息和结束时间。
- `ai_trace_span` 保存执行节点、父子关系、状态、耗时、Token、输入/输出摘要、错误类型和错误信息。
- Agent 节点：`MEMORY_CONTEXT`、`TASK_UNDERSTANDING`、`AGENT_DECISION`、`TOOL_CALL`、`FINAL_ANSWER`。
- RAG 节点：`RAG_RETRIEVAL`；直接 RAG 另有 `LLM_GENERATION`。
- 工具异常、模型异常、检索异常和超时都会写入失败 Span 或 Trace 错误信息。
- Token 统计：解析 Qwen usage，汇总记忆摘要、任务理解、每轮决策和最终答案。
- 耗时统计：Trace 总耗时 + 每个 Span 耗时。
- Trace 查询 API：列表、详情、Span、Evidence。
- 前端时间线页面留到 Phase 8，本阶段提供完整查询数据和父子关系。

## 数据模型与迁移

新增 `V5__add_phase6_trace.sql`：

- `ai_trace` 新增 `prompt_tokens`、`completion_tokens`、`error_message`、`finished_at`。
- `ai_trace_span` 新增 `input_tokens`、`output_tokens`、`error_type`。
- 新增 `idx_trace_user_created` 和 `idx_span_trace_created`。

Span 通过 `parent_span_id` 形成树。例如一次 Agent 决策下可以有多个工具调用节点；`RAG_RETRIEVAL` 目前由知识模块以根节点写入，不嵌套到 `TOOL_CALL`：

```text
TRACE
  MEMORY_CONTEXT
  TASK_UNDERSTANDING
  AGENT_DECISION
    TOOL_CALL (knowledgeSearch)
    TOOL_CALL (getDocumentDetail)
  RAG_RETRIEVAL
  FINAL_ANSWER
```

## 新增接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/traces` | 查询当前用户最近 Trace |
| GET | `/api/v1/traces/{traceId}` | 查询 Trace 主记录和全部 Span |
| GET | `/api/v1/traces/{traceId}/spans` | 查询平铺 Span，可按 parentSpanId 构建树 |
| GET | `/api/v1/traces/{traceId}/evidences` | 查询该 Trace 的 Evidence |

所有接口都要求 `ownerId`，并先校验 Trace 归属，避免跨用户读取。

## 核心实现逻辑

### Trace 与 Span 记录

`RagTraceService` 负责：

- `start`：创建 Trace，设置 RUNNING。
- `finish`：写入状态、总耗时、回答、输入/输出 Token、错误信息和结束时间。
- `startSpan` / `finishSpan`：记录节点生命周期；句柄包含 spanId 和开始时间。
- `failSpan`：记录失败节点、错误类型和错误信息。
- `recordSpan`：一次性写入没有嵌套关系的外部节点，例如 RAG 检索。
- `list` / `get` / `spans`：按归属查询。

Span 写入采用失败开放策略：数据库异常只记录日志，不阻断回答。Trace 本身的创建和收尾仍与请求生命周期绑定，保证响应能返回 traceId。

### 模型 Token

`ChatCompletionGateway.generateWithUsage` 是新增的带用量方法，默认实现兼容只返回文本的测试替身。`QwenChatGateway` 解析 OpenAI 兼容响应的 `usage.prompt_tokens` 和 `usage.completion_tokens`；解析失败或缺失时按 0 计，不影响回答。

Agent 汇总：

- 会话增量摘要 usage；
- 任务理解 usage；
- 每轮 `AGENT_DECISION` usage；
- 最终答案 usage。

RAG 问答汇总 `LLM_GENERATION` 的输入/输出 Token。

### 失败节点

- 任务理解异常：`TASK_UNDERSTANDING` 失败。
- 模型调用异常：`AGENT_DECISION` 或 `LLM_GENERATION` 失败。
- 工具异常/超时/参数非法：`TOOL_CALL` 失败，带 `errorType`。
- 检索异常：`RAG_RETRIEVAL` 失败，带错误信息。
- 最终答案降级：`FINAL_ANSWER` 标记 FAILED 和 `MODEL_FALLBACK`。
- 整体异常：Trace 状态 FAILED，写入 `errorMessage`。

## 自动化验证结果

2026-11-26 使用临时隔离的 OpenJDK 21.0.13.1 和项目 Maven Wrapper 3.9.11，在不含历史构建产物的源码副本中执行：

```bash
./mvnw clean package
```

结果成功，8 个 Maven 模块全部打包，默认单元与上下文测试共 **141 项，全部通过**。

| 测试范围 | 数量 | 覆盖内容 |
| --- | ---: | --- |
| Trace/Span 服务 | 5 | Span 开始/结束、Token/耗时、Trace 收尾、失败错误、归属校验、列表和一次性 Span |
| Trace Web 接口 | 3 | 列表/详情、Span/Evidence、非法 traceId |
| Qwen Token 解析 | 1 | `usage.prompt_tokens` / `usage.completion_tokens` |
| 既有 Phase 1-5 测试 | 132 | 原有 RAG、Agent、记忆、检索、Evidence、Web 和上下文测试 |

RAG 检索测试同时验证了 `RetrievalTraceRecorder` 写入成功 Span。外部 Qwen、Reranker、Redis 和 PostgreSQL 在默认测试中由确定性替身或 Mock 替代。

## 本机验收步骤

启动 PostgreSQL、Redis、Qdrant，配置 `QWEN_API_KEY`，执行一次 Agent 问答并记录响应 `traceId`：

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"订单模块的最大连接数是多少？","knowledgeBaseIds":[1],"ownerId":1,
       "conversationId":"conv_trace_001"}'
```

查询 Trace 详情：

```bash
curl 'http://localhost:8080/api/v1/traces/替换为traceId?ownerId=1'
```

检查：

- `trace.status` 为 SUCCESS；
- `trace.totalDuration` 为正数；
- `trace.promptTokens`、`completionTokens`、`totalTokens` 与模型用量一致；
- `spans` 包含 `MEMORY_CONTEXT`、`TASK_UNDERSTANDING`、`AGENT_DECISION`、`FINAL_ANSWER`；
- 调用知识库工具时包含 `TOOL_CALL` 和 `RAG_RETRIEVAL`；
- 失败节点 `status=FAILED` 且有 `errorType` / `errorMessage`。

查询平铺 Span 和 Evidence：

```bash
curl 'http://localhost:8080/api/v1/traces/替换为traceId/spans?ownerId=1'
curl 'http://localhost:8080/api/v1/traces/替换为traceId/evidences?ownerId=1'
```

验证失败节点：

- 将 `QWEN_API_KEY` 置空，调用 Agent，响应返回 `50301`，用返回或日志中的 traceId 查询 Trace，应看到失败状态和模型错误。
- 将 `AGENT_TOOL_TIMEOUT` 调小并临时停止 Qdrant，检查 `TOOL_CALL`/`RAG_RETRIEVAL` 的 FAILED 节点。

## 尚待真实环境验收与已知限制

- 真实 Qwen usage 和真实 PostgreSQL/Redis/Qdrant 集成尚未在 Docker 环境执行；默认测试使用替身。
- 前端 Trace 时间线、Agent 执行监控和失败节点可视化属于 Phase 8。
- Span 只保存摘要，不保存完整提示词、文档正文或密钥；排障需要结合应用日志。
- Span 写入失败开放，观测数据库异常时可能缺少节点，但业务请求仍可完成。
- 记忆提取和摘要的 Token 计入 Agent Trace；单独调用 `POST /conversations/{id}/memories/extract` 目前不创建独立 Trace。
- Reranker 没有独立 Span，包含在 `RAG_RETRIEVAL` 的耗时中；如需精确拆分可在后续增加 `RERANK` 节点。
- `ownerId` 仍不是身份认证，Trace 查询只能在受控开发环境使用。
