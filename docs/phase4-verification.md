# Phase 4 验收记录

## 已实现范围

Phase 4 实现了完整的 Agent 基础链路：

- 规则预处理：空输入、长度、控制字符清洗、固定问候/历史/文档检索意图、显式知识库范围解析。
- LLM 结构化任务理解：Qwen JSON 模式输出 `taskType`、`userIntent`、`entities`、`keywords`、`knowledgeBaseIds`、`timeRange`、`requiredTools`、`confidence`。
- 任务分类：`KNOWLEDGE_QA`、`HISTORY_QUERY`、`DOCUMENT_SEARCH`、`GENERAL_CHAT`、`UNKNOWN`。
- Tool Registry：启动时校验工具名唯一和参数 Schema，运行时只允许白名单工具。
- Function Calling：通过 DashScope Qwen OpenAI 兼容接口发送工具声明并解析 `tool_calls`。
- ReAct 执行循环：Thought → Action → Observation → 最终答案，支持同一轮多个工具和跨轮连续调用。
- 工具异常处理：未知工具、非法参数、业务异常、未预期异常、执行器拒绝和超时均转换为失败 Observation。
- 执行限制：最大推理轮数、最大工具调用次数、整体执行超时、单工具超时、连续失败上限。
- SSE 流式回答：`start`、`task`、`step`、`answer_delta`、`done`、`error` 六类事件。
- 引用安全：全局证据编号、Chunk 去重、越界引用过滤、Evidence `cited` 标记。
- 轻量追踪：复用 `ai_trace` 记录问题、知识库范围、任务类型、状态、回答和耗时。

本阶段没有新增数据库迁移，也没有提前实现 Phase 5 记忆或 Phase 6 完整 Span。

## 新增接口

### `POST /api/v1/agent/chat`

非流式 Agent 对话。请求示例：

```json
{
  "message": "订单模块的最大连接数是多少？",
  "knowledgeBaseIds": [1],
  "ownerId": 1,
  "topK": 5,
  "conversationId": "conv_001",
  "history": [
    {"role": "user", "content": "我们刚才在讨论订单模块。"},
    {"role": "assistant", "content": "好的。"}
  ],
  "documentIds": []
}
```

响应 `data` 包含：

- `answer`：最终答案。
- `citations`：真实证据，包含 Chunk、文档、版本、页码、原文和检索分数。
- `traceId`：可用于查询 Evidence。
- `taskType`、`taskUnderstanding`：任务分类与结构化理解结果。
- `steps`：`THOUGHT`、`ACTION`、`OBSERVATION` 执行步骤。
- `termination`：`COMPLETED`、`MAX_ITERATIONS`、`MAX_TOOL_CALLS`、`TIMEOUT`、`TOOL_FAILURE_LIMIT`、`MODEL_ERROR`。
- `warnings`：降级、越界引用、连续失败等提示。
- `usage`：输入/输出 Token 用量。
- `conversationId`：原样回传，Phase 4 不持久化会话。

### `POST /api/v1/chat/stream`

SSE 流式 Agent 对话，事件顺序示例：

```text
event:start
data:{"traceId":"..."}

event:task
data:{"taskType":"KNOWLEDGE_QA",...}

event:step
data:{"type":"ACTION","name":"knowledgeSearch",...}

event:step
data:{"type":"OBSERVATION","name":"knowledgeSearch",...}

event:answer_delta
data:{"delta":"订单模块最大连接数为 30 "}

event:answer_delta
data:{"delta":"[1]。"}

event:done
data:{"answer":"...","citations":[...],"termination":"COMPLETED",...}
```

错误事件：

```text
event:error
data:{"code":"50301","message":"...","traceId":"..."}
```

SSE 接口在流式模式下采用“移除非法引用标记”的策略，因为已发送文本无法撤回；普通 RAG 接口仍保持整段拒绝。

## 工具与权限边界

| 工具 | 实现状态 | 边界 |
| --- | --- | --- |
| `knowledgeSearch` | 完整实现 | 只能使用请求允许的知识库，参数只能收窄范围 |
| `documentSearch` | 完整实现 | 先校验文档归属，再按文档 ID 过滤候选 |
| `getDocumentDetail` | 完整实现 | 返回元信息，不暴露服务器文件路径 |
| `historyMemorySearch` | Phase 5 占位 | 返回 `MEMORY_NOT_AVAILABLE`，不伪造历史经验 |
| `getConversationHistory` | 请求内历史 | 只接受 user/assistant，system 角色被丢弃 |

`documentSearch` 由于底层检索不支持按文档下推过滤，采用“扩大候选集后精确过滤”的实现；候选集上限为 20，可能遗漏候选集之外的文档分块。

## 配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENT_MAX_ITERATIONS` | `5` | 最大模型推理轮数 |
| `AGENT_MAX_TOOL_CALLS` | `8` | 单次请求最大工具调用总数 |
| `AGENT_MAX_CONSECUTIVE_FAILURES` | `3` | 连续工具失败上限 |
| `AGENT_MAX_EVIDENCE` | `12` | 单次请求最多证据数 |
| `AGENT_MAX_HISTORY_MESSAGES` | `20` | 请求历史消息上限 |
| `AGENT_MAX_QUESTION_LENGTH` | `4000` | 问题长度上限 |
| `AGENT_MAX_OBSERVATION_CHARS` | `6000` | 工具观察字符上限 |
| `AGENT_MAX_CHUNK_CHARS` | `1200` | 单条证据在提示词中的字符上限 |
| `AGENT_EXECUTION_TIMEOUT` | `90s` | 整体执行超时 |
| `AGENT_TOOL_TIMEOUT` | `20s` | 单工具执行超时，必须不大于整体超时 |

Agent 使用独立的 `agentToolExecutor` 有界线程池和 `agentStreamExecutor` 虚拟线程池。

## 自动化验证结果

2026-11-26 使用临时隔离的 OpenJDK 21.0.13.1 和项目 Maven Wrapper 3.9.11，在不含历史构建产物的源码副本中执行：

```bash
./mvnw clean package
```

结果成功，7 个 Maven 模块全部打包，默认单元与上下文测试共 **94 项，全部通过**。

| 测试范围 | 数量 | 覆盖内容 |
| --- | ---: | --- |
| 规则预处理 | 7 | 空输入、长度、控制字符、固定意图、知识库范围交集与越界 |
| 结构化任务理解 | 6 | 合法 JSON、越界范围、未知工具、非法 JSON 降级、低置信度、非法时间范围、代码块包裹 |
| Tool Registry | 5 | 重复工具名、未知工具、非法 JSON、工具异常脱敏、参数白名单 |
| 五个 Agent 工具 | 9 | 证据渲染与去重、范围收窄、无命中、文档详情脱敏、文档过滤、历史工具降级、会话历史上限 |
| 流式引用过滤 | 5 | 跨增量引用、越界编号、非数字方括号、未闭合方括号、溢出编号 |
| ReAct 循环 | 12 | 普通对话禁用工具、多工具连续调用、工具失败、最大轮数、工具上限、超时、连续失败、越界引用、SSE 增量、模型降级、首次失败、无权限提前拒绝 |
| Qwen Agent 网关 | 6 | 工具声明与解析、JSON 模式、SSE 解析、HTTP 错误、缺少密钥、响应超时 |
| Web 接口与 SSE | 3 | 非流式响应字段、参数与角色校验、SSE 事件顺序与事件名 |
| Phase 2/3 既有测试 | 41 | 原有 RAG、检索、解析、Evidence、Web 接口和上下文测试 |

SSE 控制器测试验证了 `start`、`task`、`step`、`answer_delta`、`done` 事件和 `traceId`；非流式控制器测试验证了 `taskType`、`termination`、`citations`、`steps` 和雪花 ID 字符串序列化。

外部 Qwen 与 Reranker 仍由本地确定性 HTTP 替身替换，不消耗付费配额；这不代表真实模型效果验收。

## 本机验收步骤

启动 PostgreSQL、Redis、Qdrant，配置 `QWEN_API_KEY`，完成文档入库后执行：

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"订单模块的最大连接数是多少？","knowledgeBaseIds":[1],"ownerId":1,"topK":5}'
```

检查响应中的 `taskType`、`taskUnderstanding.requiredTools`、`steps`、`citations`、`termination` 和 `traceId`。随后查询证据：

```bash
curl 'http://localhost:8080/api/v1/evidence/替换为traceId?ownerId=1'
```

SSE 验证：

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"message":"订单模块的最大连接数是多少？","knowledgeBaseIds":[1],"ownerId":1}'
```

应依次看到 `start`、`task`、若干 `step`、`answer_delta` 和 `done`。

不同问题类型验证：

- `你好`：应分类为 `GENERAL_CHAT`，不调用工具。
- `帮我找哪些文档提到连接池`：应分类为 `DOCUMENT_SEARCH`，优先调用 `documentSearch`。
- `我们之前是怎么解决连接池问题的？`：应分类为 `HISTORY_QUERY`，先尝试 `historyMemorySearch`，返回 Phase 5 未启用后继续使用知识库检索或说明限制。
- 需要同时查文档内容和元信息的问题：检查 `steps` 中是否出现多个 `ACTION` 和 `OBSERVATION`。

异常与终止条件验证：

- 将 `AGENT_MAX_TOOL_CALLS=1` 后提出需要多次检索的问题，响应 `termination` 应为 `MAX_TOOL_CALLS`。
- 将 `AGENT_TOOL_TIMEOUT=1s` 并临时停止 Qdrant，工具应以失败 Observation 返回，Agent 应生成降级回答而不是抛出未处理异常。
- 将 `QWEN_API_KEY` 置空，首次模型调用返回 `50301`；若已有工具观察，则返回 `termination=MODEL_ERROR` 的降级答案。
- 模型返回越界引用时，最终答案中不应出现该编号，`warnings` 应记录移除数量。

## 尚待真实环境验收与已知限制

- 真实 DashScope Qwen Function Calling、JSON 模式和 SSE 流式输出尚未使用付费配额验收；需要固定问题集检查工具选择、参数格式和流式兼容性。
- 任务分类准确率、工具选择准确率尚未量化，属于 Phase 7 评测体系。
- `historyMemorySearch` 和会话持久化属于 Phase 5；当前 `getConversationHistory` 只使用请求携带的历史。
- Agent 执行步骤目前通过响应和 SSE 返回，未单独持久化到 Span 表；完整节点记录、Token 统计和前端 Trace 可视化属于 Phase 6。
- `documentSearch` 的候选集过滤是近似实现，大文档集下可能遗漏；后续应在检索层增加文档 ID 下推过滤。
- SSE 在反向代理后可能受缓冲和空闲超时影响，生产部署需要关闭代理缓冲并设置足够超时。
- `ownerId` 仍不是身份认证，当前版本只能用于受控开发环境。
