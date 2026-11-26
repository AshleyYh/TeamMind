# Phase 5 验收记录

## 已实现范围

Phase 5 实现了三层记忆中的短期记忆和长期记忆，知识库仍由 Phase 2/3 独立负责：

- Redis 短期会话记忆：按 `conversationId` 隔离，保存最近消息和增量摘要。
- 上下文窗口限制：只把最近 `MEMORY_CONTEXT_MESSAGES` 条消息放入提示词。
- 历史消息裁剪：超过摘要阈值后先合并摘要，再裁剪 Redis。
- 增量摘要：已有摘要与新增旧消息合并，摘要失败时保留原消息。
- 会话过期：消息和摘要 key 设置 TTL。
- 长期记忆提取：从会话提取 DRAFT 候选，服务端校验类型、内容和置信度。
- 长期记忆确认：只有确认后的 ACTIVE 记忆参与 Agent 检索。
- 长期记忆更新、失效和逻辑删除：更新递增版本，失效/删除保留来源用于追溯。
- 长期记忆检索：按用户、ACTIVE 状态、关键词和中文二元词检索，更新 `lastUsedAt`。
- 来源追溯：保存 `sourceConversationId`、`sourceTraceId`、版本、创建/更新时间和使用时间。
- Agent 集成：`conversationId` 加载短期上下文，回答后写回消息，`ai_trace` 关联会话。
- `historyMemorySearch` 从 Phase 4 占位实现替换为真实长期记忆检索。

## 新增接口

### 会话

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/conversations` | 查询当前用户的会话列表 |
| GET | `/api/v1/conversations/{conversationId}/messages` | 查询增量摘要和最近消息 |
| DELETE | `/api/v1/conversations/{conversationId}` | 逻辑删除会话并清理 Redis |
| POST | `/api/v1/conversations/{conversationId}/memories/extract` | 从会话提取长期记忆 DRAFT 候选 |

### 长期记忆

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/memories` | 按状态、类型和条数查询记忆 |
| GET | `/api/v1/memories/search` | 检索 ACTIVE 记忆 |
| GET | `/api/v1/memories/{id}` | 查询记忆详情和来源 |
| POST | `/api/v1/memories` | 新增 DRAFT 记忆 |
| PUT | `/api/v1/memories/{id}` | 修改记忆并递增版本 |
| POST | `/api/v1/memories/{id}/confirm` | 确认 DRAFT 为 ACTIVE |
| POST | `/api/v1/memories/{id}/invalidate` | 使记忆失效 |
| DELETE | `/api/v1/memories/{id}` | 逻辑删除并标记失效 |

Agent 请求新增/扩展 `conversationId`，响应原样返回该标识；SSE 事件结构不变。

## 数据库与 Redis 变更

新增 Flyway 迁移 `V4__add_phase5_memory.sql`：

- `ai_memory` 新增 `title`、`summary`、`source_trace_id`、`confidence`、`last_used_at`、`deleted`。
- 新增置信度约束、用户/状态/更新时间索引和来源 Trace 索引。
- 复用 `ai_conversation`、`ai_trace.conversation_id` 和 `ai_memory.source_conversation_id`。

Redis key：

```text
teammind:memory:conversation:{conversationId}:messages
teammind:memory:conversation:{conversationId}:summary
```

两类 key 都设置 `MEMORY_CONVERSATION_TTL` 过期时间，默认 7 天。

## 核心实现逻辑

### 短期记忆

`ConversationService` 使用 PostgreSQL 保存会话归属和标题，使用 Redis List 保存消息 JSON。每次追加消息后按 `MEMORY_MAX_MESSAGES` 裁剪并刷新 TTL。读取时只接受当前归属用户，Redis 异常转换为 `50301`。

`ConversationSummaryService` 在消息超过 `MEMORY_SUMMARY_THRESHOLD` 时：

1. 取最近窗口之外的旧消息。
2. 与已有摘要一起交给对话模型生成新摘要。
3. 保存摘要和累计摘要消息数。
4. 裁剪 Redis，只保留最近 `MEMORY_CONTEXT_MESSAGES` 条。
5. 摘要失败时保留原消息并返回警告，不丢历史。

Agent 把摘要作为“历史会话摘要”加入任务理解和 Agent 上下文，但明确标注它不是知识库证据。

### 长期记忆

`MemoryService` 的所有查询都带 `userId` 和逻辑删除条件。新增记忆固定为 DRAFT，确认后才变为 ACTIVE。修改内容时版本递增；失效和删除都会把状态改为 INVALID。来源会话必须属于当前用户，否则拒绝。

`MemoryExtractionService` 用 LLM 输出 JSON 数组，服务端逐条校验：

- 类型必须属于 `FACT`、`PREFERENCE`、`DECISION`、`EXPERIENCE`、`PROFILE`、`OTHER`。
- 内容不能为空，长度受限。
- 置信度必须在 0 到 1 之间，低于 0.5 的候选跳过。
- 单次最多保存 `MEMORY_MAX_EXTRACTION_CANDIDATES` 条。
- 提示词明确排除密码、密钥、令牌和一次性信息。

提取结果仍是 DRAFT，不会直接影响后续回答；用户确认后才进入 `historyMemorySearch`。

### Agent 集成

```text
conversationId
  -> ensure 会话元数据
  -> 加载 Redis 消息和摘要
  -> 任务理解 + ReAct
  -> 回答
  -> 写回 user/assistant 消息
  -> ai_trace.conversation_id
```

`historyMemorySearch` 只返回 ACTIVE 记忆，输出使用“记忆#n”编号，提示词明确禁止把它写成文档引用 `[n]`。

## 自动化验证结果

2026-11-26 使用临时隔离的 OpenJDK 21.0.13.1 和项目 Maven Wrapper 3.9.11，在不含历史构建产物的源码副本中执行：

```bash
./mvnw clean package
```

结果成功，8 个 Maven 模块全部打包，默认单元与上下文测试共 **132 项，全部通过**。

| 测试范围 | 数量 | 覆盖内容 |
| --- | ---: | --- |
| 会话 Redis 服务 | 8 | 创建/归属校验、追加裁剪、TTL、损坏 JSON、Redis 不可用、删除清理、摘要 key、非法标识 |
| 长期记忆服务 | 8 | 草稿创建、去重、确认、失效、版本递增、逻辑删除、ACTIVE 检索、来源与置信度校验 |
| 增量摘要 | 6 | 阈值触发、未触发、摘要失败保留历史、已有摘要合并、Redis 降级、无会话标识 |
| 记忆提取 | 4 | 合法/非法候选、对象包裹数组、非法 JSON、空会话 |
| Agent 会话集成 | 2 | 回答后写入短期记忆、摘要和历史传入任务理解 |
| 历史记忆工具（净增） | 1 | 替换 Phase 4 占位实现，覆盖 ACTIVE 记忆渲染和无命中 |
| 会话与记忆 Web | 9 | 草稿创建、参数校验、确认、列表/检索、会话消息、删除、提取、路径校验 |
| Phase 1-4 既有测试 | 94 | 原有 RAG、Agent、检索、Evidence、Web 和上下文测试 |

外部 Qwen、Reranker、Redis 和 PostgreSQL 在默认测试中由确定性替身或 Mock 替代，不消耗付费配额；这不代表真实模型效果和真实 Redis/PostgreSQL 集成验收。

## 本机验收步骤

启动 PostgreSQL、Redis、Qdrant，配置 `QWEN_API_KEY`，完成文档入库后：

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"订单模块的最大连接数是多少？","knowledgeBaseIds":[1],"ownerId":1,
       "conversationId":"conv_001"}'
```

用同一 `conversationId` 追问：

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"那它的超时时间呢？","knowledgeBaseIds":[1],"ownerId":1,
       "conversationId":"conv_001"}'
```

检查第二次响应的 `taskUnderstanding` 或 `warnings`，确认上下文包含上一轮信息。查看短期记忆：

```bash
curl 'http://localhost:8080/api/v1/conversations/conv_001/messages?ownerId=1&limit=50'
```

提取长期记忆候选：

```bash
curl -X POST 'http://localhost:8080/api/v1/conversations/conv_001/memories/extract?ownerId=1'
```

确认候选：

```bash
curl -X POST 'http://localhost:8080/api/v1/memories/记忆ID/confirm?ownerId=1'
```

换一个新会话询问同一事实，观察 `steps` 中是否出现 `historyMemorySearch` 和“记忆#”内容：

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"我们之前确认过订单模块的什么配置？","knowledgeBaseIds":[1],"ownerId":1,
       "conversationId":"conv_002"}'
```

查看记忆来源、更新和失效：

```bash
curl 'http://localhost:8080/api/v1/memories/记忆ID?ownerId=1'
curl -X PUT 'http://localhost:8080/api/v1/memories/记忆ID' \
  -H 'Content-Type: application/json' \
  -d '{"ownerId":1,"memoryType":"FACT","title":"订单连接数","content":"订单模块最大连接数为 50"}'
curl -X POST 'http://localhost:8080/api/v1/memories/记忆ID/invalidate?ownerId=1'
```

检查 Redis TTL：

```bash
redis-cli TTL 'teammind:memory:conversation:conv_001:messages'
```

应返回正数秒数，默认约 7 天。

## 尚待真实环境验收与已知限制

- 真实 Qwen 摘要、记忆提取和 Function Calling 尚未用付费配额验收；需要固定会话集检查摘要质量和候选准确性。
- 默认测试使用 Mock Redis 和 Mock 数据库，真实 PostgreSQL 16 + Redis 7 集成需要在 Docker 环境执行。
- 长期记忆检索使用关键词和中文二元词，没有向量召回；大量记忆场景需要后续增加 Embedding 或 PostgreSQL 全文索引。
- 记忆提取需要显式调用接口并人工确认，尚未实现自动提取、前端审批和批量确认。
- 会话删除不会删除长期记忆，来源会话/Trace 字段保留用于追溯；如需彻底清理需要单独提供隐私删除流程。
- `ownerId` 仍不是身份认证，任意调用者可伪造归属，当前版本只能用于受控开发环境。
- 完整 Span/Token 追踪、量化评测和前端业务页面属于 Phase 6-8。
