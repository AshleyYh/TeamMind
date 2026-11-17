# TeamMind 企业级研发知识库 Agent 平台

## 项目需求与技术开发文档

**文档版本：** V1.0  
**项目类型：** 企业级 AI Agent / RAG 知识管理平台  
**开发模式：** AI 辅助开发 + 分阶段迭代  
**后端语言：** Java 21  
**后端框架：** Spring Boot 3.x

---

# 一、项目概述

## 1.1 项目背景

在企业研发过程中，会产生大量技术文档、接口文档、数据库设计文档、项目规范、故障处理记录和会议纪要。

这些知识通常分散在不同的文档、系统和人员手中，存在以下问题：

1. 企业知识分散，研发人员查找资料成本较高。
    
2. 新员工获取项目历史信息困难，依赖老员工传授经验。
    
3. 传统 RAG 只能完成简单的文档检索和问答，缺少任务理解与自主执行能力。
    
4. 多轮对话缺少有效的记忆机制，无法持续理解用户需求。
    
5. AI 回答缺少可靠的证据引用，难以判断答案是否可信。
    
6. Agent 执行过程不透明，出现问题后难以定位失败原因。
    

因此，本项目计划开发一个基于 Java 和大语言模型的企业级研发知识库 Agent 平台。

## 1.2 项目目标

构建一个支持文档管理、混合检索、智能问答、Agent 工具调用、短期记忆、长期记忆和全链路执行追踪的企业级 AI 应用。

核心目标：

- 实现企业文档自动解析、分块、向量化和知识入库。
    
- 实现 Dense + BM25 混合检索及 RRF 融合。
    
- 实现基于 BGE-Reranker 的检索结果重排序。
    
- 实现基于 ReAct 的 Agent 任务理解和工具调用。
    
- 实现短期记忆、长期记忆和知识库三层架构。
    
- 实现答案证据引用和原始文档溯源。
    
- 实现 Agent 全链路 Trace ID 追踪。
    
- 建立 Agent 和 RAG 的自动化评测体系。
    

## 1.3 项目定位

本项目不是简单的 AI 聊天机器人，而是一个具备任务规划、工具执行、知识检索、记忆管理和可观测性的企业级 AI Agent 系统。

# 二、技术选型

## 2.1 后端技术栈

|技术|选型|用途|
|---|---|---|
|开发语言|Java 21|核心业务开发|
|后端框架|Spring Boot 3.x|应用基础框架|
|AI 框架|LangChain4j|LLM、Agent、RAG|
|大语言模型|Qwen|任务理解和答案生成|
|Embedding|BGE / Qwen Embedding|文本向量化|
|Reranker|BGE-Reranker|检索结果重排序|
|向量数据库|Qdrant|向量检索|
|关系数据库|PostgreSQL|业务数据及元数据|
|缓存|Redis|会话、缓存和短期状态|
|文档解析|Apache Tika / PDFBox|文档解析|
|ORM|MyBatis-Plus|数据持久化|
|接口文档|SpringDoc OpenAPI|API 文档|
|构建工具|Maven|项目构建|

注意：

- Qwen 模型通过可配置的模型客户端接入，不能将模型地址和 API Key 写死在代码中。
    
- Embedding 和 Reranker 应设计为独立服务接口，方便后续更换模型。
    
- Qdrant 负责向量检索，PostgreSQL 保存文档、分块、版本、引用和业务数据。
    
- BM25 检索可以采用 PostgreSQL 全文检索实现第一版，后续根据效果决定是否引入独立搜索引擎。
    

## 2.2 前端技术栈

|技术|选型|用途|
|---|---|---|
|框架|Vue 3|前端应用|
|开发语言|TypeScript|类型安全|
|构建工具|Vite|项目构建|
|UI|Element Plus|管理界面|
|状态管理|Pinia|前端状态管理|
|HTTP|Axios|接口请求|
|实时通信|SSE|AI 流式输出|

## 2.3 基础设施

开发环境优先使用 Docker Compose 管理 PostgreSQL、Redis 和 Qdrant。

生产部署时支持独立部署各基础服务。

---

# 三、系统功能设计

系统分为八个核心模块。

## 3.1 用户与知识库管理模块

主要功能：

1. 用户登录及身份认证。
    
2. 用户基础信息管理。
    
3. 创建、编辑、删除知识库。
    
4. 知识库权限管理。
    
5. 知识库文档管理。
    
6. 文档上传及解析状态查询。
    
7. 文档版本管理。
    

知识库数据需要支持多知识库隔离。

每份文档至少需要记录：

- 文档 ID
    
- 知识库 ID
    
- 文档名称
    
- 文件类型
    
- 文件存储位置
    
- 文档版本
    
- 文档状态
    
- 创建时间
    
- 更新时间
    

文档状态：

PENDING、PROCESSING、COMPLETED、FAILED。

## 3.2 文档处理与知识入库模块

实现完整的文档处理流水线：

文件上传 → 文件校验 → 文档解析 → 文本清洗 → 文本分块 → Embedding → Qdrant 入库 → PostgreSQL 元数据保存。

支持的文件类型：

- PDF
    
- DOCX
    
- TXT
    
- Markdown
    

分块策略：

第一版采用固定长度分块 + Overlap。

建议初始参数：

- Chunk Size：500～800 tokens。
    
- Chunk Overlap：80～120 tokens。
    

参数应当可以配置，不能散落在业务代码中。

每个 Chunk 必须保存以下元数据：

- chunk_id
    
- document_id
    
- knowledge_base_id
    
- document_version
    
- chunk_index
    
- content
    
- source_file
    
- page_number（适用时）
    
- created_at
    

要求：

1. 每个 Chunk 必须能够追溯到原始文档。
    
2. 文档重新上传时支持版本管理。
    
3. 文档删除时，需要同步处理关联向量。
    
4. 文档解析失败时记录失败原因。
    
5. 文档处理支持异步执行。
    
6. 文档入库应支持幂等处理。
    

## 3.3 RAG 混合检索模块

这是项目的核心模块之一。

实现 Dense + Sparse 混合检索架构。

检索流程：

用户问题 → 查询预处理 → Dense Retrieval + BM25 Retrieval → RRF 融合 → Reranker 重排序 → 文档版本过滤 → 返回 Top K 证据。

### Dense Retrieval

通过 Embedding 模型将用户问题转化为向量，在 Qdrant 中进行相似度检索。

支持：

- Top K
    
- 相似度阈值
    
- 知识库过滤
    
- 文档版本过滤
    

### BM25 Retrieval

通过关键词匹配进行全文检索。

重点解决：

- 类名匹配
    
- 方法名匹配
    
- 错误码匹配
    
- 专有名词匹配
    
- 精确关键词匹配
    

### RRF 融合

使用 Reciprocal Rank Fusion 融合多个检索结果。

计算公式：

RRF(d) = Σ 1 / (k + rank_i(d))

其中：

- d：检索文档。
    
- rank_i(d)：文档在第 i 个检索结果中的排名。
    
- k：平滑参数，初始设置为 60。
    

要求：

1. 同一 Chunk 的多路检索结果必须正确合并。
    
2. 保留原始检索排名和融合分数。
    
3. 检索过程可以追踪。
    
4. 支持调整 Dense 和 BM25 的召回数量。
    

### BGE-Reranker

将融合后的候选文档与用户问题一起发送给 Reranker。

根据相关性重新排序，选择最终交给 LLM 的上下文。

Reranker 应通过统一接口封装，避免业务代码直接依赖具体模型服务。

### 文档版本过滤

检索时必须确保：

- 已删除文档不能被检索。
    
- 已失效版本不能作为当前有效证据。
    
- 用户只能检索有权限访问的知识库。
    
- 引用必须指向实际存在的文档和 Chunk。
    

---

## 3.4 Agent 智能任务处理模块

这是整个项目最重要的模块。

采用：

**规则预处理 + LLM 结构化任务理解 + ReAct 执行机制。**

Agent 不应该直接把所有用户问题交给大模型，而应该先进行任务识别，再决定后续执行路径。

### 任务类型

第一版定义以下任务类型：

1. KNOWLEDGE_QA：知识库问答。
    
2. HISTORY_QUERY：历史经验查询。
    
3. DOCUMENT_SEARCH：文档检索。
    
4. GENERAL_CHAT：普通聊天。
    
5. UNKNOWN：无法识别的任务。
    

### 执行流程

用户输入 → 规则预处理 → LLM 任务分类 → 结构化任务解析 → Agent 选择执行工具 → 获取工具结果 → 判断是否继续执行 → 生成最终答案。

### 规则预处理

负责处理：

- 空输入校验。
    
- 特殊字符处理。
    
- 简单固定意图识别。
    
- 用户上下文提取。
    
- 明确的知识库范围约束。
    

规则层不承担复杂语义理解。

### LLM 结构化任务理解

使用 Structured Output 将模型输出约束为结构化对象。

建议定义 TaskUnderstanding：

- taskType
    
- userIntent
    
- entities
    
- keywords
    
- knowledgeBaseIds
    
- timeRange
    
- requiredTools
    
- confidence
    

必须进行 JSON 格式校验和参数合法性校验。

不能完全信任 LLM 输出的参数。

### ReAct 执行机制

实现标准的：

Thought → Action → Observation → Thought → Final Answer。

具体实现要求：

1. Agent 根据任务选择工具。
    
2. 工具执行结果返回 Agent。
    
3. Agent 根据 Observation 决定下一步。
    
4. 支持多轮工具调用。
    
5. 支持最大执行次数。
    
6. 支持执行超时。
    
7. 支持工具调用异常捕获。
    
8. 达到终止条件后生成最终答案。
    

建议第一版自己实现一个可控的 Agent 执行循环，以便理解 ReAct 原理。

不要一开始就将整个执行过程封装成一个无法观察内部状态的黑盒。

### 第一版工具列表

实现以下工具：

- knowledgeSearch：知识库检索。
    
- documentSearch：指定文档检索。
    
- historyMemorySearch：历史经验查询。
    
- getDocumentDetail：获取文档详情。
    
- getConversationHistory：获取会话历史。
    

工具需要统一注册和管理。

每个工具都需要定义：

- 工具名称
    
- 工具描述
    
- 输入参数
    
- 参数校验
    
- 执行方法
    
- 执行结果
    
- 异常处理
    

工具必须具有明确的权限边界，不能允许 LLM 任意访问数据库或执行任意代码。

---

## 3.5 记忆管理模块

采用三层记忆架构：

### 第一层：短期记忆

负责保存当前会话上下文。

存储方式：

Redis + LangChain4j ChatMemory。

实现：

1. 按 conversationId 隔离会话。
    
2. 保存最近 N 轮对话。
    
3. 支持上下文窗口限制。
    
4. 支持历史消息裁剪。
    
5. 支持增量摘要。
    
6. 支持会话过期。
    

### 第二层：长期记忆

长期记忆用于保存经过提取和确认的历史经验。

例如：

用户之前明确确认：

订单模块使用 HikariCP，连接池最大连接数为 30。

后续用户询问订单模块连接池配置时，可以调用长期记忆。

长期记忆需要具备：

- 记忆提取
    
- 记忆摘要
    
- 记忆分类
    
- 记忆存储
    
- 记忆检索
    
- 记忆更新
    
- 记忆失效
    
- 来源追溯
    

重要原则：

不能把所有聊天内容直接存成长期记忆。

只有经过有效性判断的内容才能进入长期记忆。

长期记忆需要保存来源和版本，避免旧信息覆盖新信息。

### 第三层：知识库

企业文档属于事实性知识。

知识库和长期记忆必须独立管理。

知识库负责企业文档事实检索。

长期记忆负责经过确认的用户历史经验。

短期记忆负责当前对话上下文。

三者不能混为一谈。

---

## 3.6 Evidence Board 证据管理模块

该模块负责管理 AI 回答使用的检索证据。

每次知识库问答都需要记录：

- Evidence ID
    
- Trace ID
    
- Document ID
    
- Chunk ID
    
- 文档版本
    
- 原文内容
    
- 检索方式
    
- 原始检索排名
    
- RRF 分数
    
- Reranker 分数
    
- 最终引用状态
    

最终答案需要关联 Evidence。

前端展示：

1. AI 回答。
    
2. 引用文档名称。
    
3. 引用内容摘要。
    
4. 原文所在页码。
    
5. 文档版本。
    
6. 点击查看原始文档。
    

服务端必须校验引用归属和原文匹配。

不能允许模型随意生成一个不存在的文档 ID 或引用位置。

如果没有有效证据，需要明确告诉用户当前知识库没有找到足够依据。

---

## 3.7 Agent 全链路追踪模块

针对 Agent 执行过程复杂、错误定位困难的问题，建立全链路 Trace ID。

一次用户请求对应一个 Trace ID。

执行链路：

Trace ID → Task Understanding → Agent Decision → Tool Calling → Memory Retrieval → RAG Retrieval → Reranker → LLM Generation → Final Answer。

每个执行节点需要记录：

- Trace ID
    
- Span ID
    
- Parent Span ID
    
- 节点类型
    
- 开始时间
    
- 结束时间
    
- 执行耗时
    
- 执行状态
    
- 异常信息
    
- Token 使用量（可获取时）
    

要求：

1. 一次 Agent 执行对应唯一 Trace ID。
    
2. 子任务共享 Trace ID。
    
3. 每个工具调用拥有独立 Span。
    
4. 能够查看完整执行顺序。
    
5. 能够定位失败节点。
    
6. 能够统计单次请求耗时。
    
7. 能够统计模型调用次数和 Token 消耗。
    

注意：

生产环境日志不能直接记录 API Key、密码和完整敏感用户信息。

---

## 3.8 Agent 评测模块

需要建立独立的评测体系，而不是只通过人工提问判断效果。

### 任务识别评测

指标：

- 任务类型识别准确率。
    
- 结构化参数提取准确率。
    
- 工具选择准确率。
    

### RAG 检索评测

指标：

- Recall@K
    
- Precision@K
    
- MRR
    
- NDCG
    

对比：

1. 纯 Dense 检索。
    
2. 纯 BM25 检索。
    
3. Dense + BM25。
    
4. Dense + BM25 + RRF。
    
5. 混合检索 + RRF + Reranker。
    

### 记忆评测

指标：

- 记忆提取准确率。
    
- 记忆召回准确率。
    
- 记忆更新正确率。
    
- 历史经验问答正确率。
    

### 最终回答评测

指标：

- 答案正确性。
    
- 答案相关性。
    
- 引用准确率。
    
- 证据支持率。
    
- 幻觉率。
    

所有评测必须保存测试数据集、测试结果和评测时间。

不能人为修改测试结果。

---

# 四、系统架构设计

## 4.1 整体架构

系统采用分层架构，业务模块之间通过接口进行交互。

核心架构：

用户浏览器  
|  
v  
Vue3 前端  
|  
v  
Spring Boot Controller  
|  
v  
Application Service  
|  
+--------------------------+  
| |  
v v  
Agent Orchestrator Document Service  
| |  
v v  
Task Understanding Document Parser  
| |  
v v  
Tool Registry Chunk Service  
| |  
v v  
Tool Execution Embedding Service  
| |  
v v  
Memory Service Vector Store  
| |  
v v  
RAG Retrieval Qdrant  
|  
v  
RRF + Reranker  
|  
v  
Evidence Board  
|  
v  
LLM Generation  
|  
v  
Final Answer

基础设施：

- PostgreSQL
    
- Redis
    
- Qdrant
    
- Qwen API
    
- Embedding API
    
- Reranker API
    

## 4.2 代码分层原则

Controller：

只处理 HTTP 请求、参数校验和响应封装。

Application Service：

负责业务流程编排。

Domain：

负责核心业务模型和领域规则。

Infrastructure：

负责数据库、Redis、Qdrant、LLM 等外部系统交互。

AI 模块：

负责 Agent、RAG、Memory、Prompt 和模型调用。

禁止：

1. Controller 直接操作数据库。
    
2. Controller 直接调用 LLM。
    
3. 将复杂 Agent 逻辑全部写进 Service。
    
4. 将 Prompt 散落在多个业务类中。
    
5. 将数据库访问和模型调用混在一个方法里。
    

---

# 五、数据库设计

第一版使用 PostgreSQL 作为关系数据库。

以下为核心表，具体字段可在开发过程中进一步完善。

## 5.1 用户表：sys_user

|字段|类型|说明|
|---|---|---|
|id|BIGINT|主键|
|username|VARCHAR|用户名|
|password|VARCHAR|加密密码|
|nickname|VARCHAR|昵称|
|status|SMALLINT|状态|
|created_at|TIMESTAMP|创建时间|

## 5.2 知识库表：knowledge_base

|字段|类型|说明|
|---|---|---|
|id|BIGINT|主键|
|name|VARCHAR|知识库名称|
|description|TEXT|描述|
|owner_id|BIGINT|创建人|
|status|SMALLINT|状态|
|created_at|TIMESTAMP|创建时间|

## 5.3 文档表：knowledge_document

|字段|类型|说明|
|---|---|---|
|id|BIGINT|主键|
|knowledge_base_id|BIGINT|知识库 ID|
|document_name|VARCHAR|文档名称|
|file_type|VARCHAR|文件类型|
|file_path|VARCHAR|文件存储路径|
|current_version|INTEGER|当前版本|
|processing_status|VARCHAR|处理状态|
|created_at|TIMESTAMP|创建时间|

## 5.4 文档分块表：knowledge_chunk

|字段|类型|说明|
|---|---|---|
|id|BIGINT|主键|
|document_id|BIGINT|文档 ID|
|knowledge_base_id|BIGINT|知识库 ID|
|document_version|INTEGER|文档版本|
|chunk_index|INTEGER|分块序号|
|content|TEXT|分块内容|
|page_number|INTEGER|页码|
|vector_id|VARCHAR|Qdrant 向量 ID|
|created_at|TIMESTAMP|创建时间|

## 5.5 会话表：ai_conversation

|字段|类型|说明|
|---|---|---|
|id|BIGINT|主键|
|conversation_id|VARCHAR|会话标识|
|user_id|BIGINT|用户 ID|
|title|VARCHAR|会话标题|
|created_at|TIMESTAMP|创建时间|

## 5.6 长期记忆表：ai_memory

|字段|类型|说明|
|---|---|---|
|id|BIGINT|主键|
|user_id|BIGINT|用户 ID|
|memory_type|VARCHAR|记忆类型|
|content|TEXT|记忆内容|
|source_conversation_id|VARCHAR|来源会话|
|version|INTEGER|记忆版本|
|status|VARCHAR|状态|
|created_at|TIMESTAMP|创建时间|
|updated_at|TIMESTAMP|更新时间|

## 5.7 Trace 表：ai_trace

|字段|类型|说明|
|---|---|---|
|id|BIGINT|主键|
|trace_id|VARCHAR|链路 ID|
|user_id|BIGINT|用户 ID|
|conversation_id|VARCHAR|会话 ID|
|status|VARCHAR|执行状态|
|total_duration|BIGINT|总耗时|
|total_tokens|BIGINT|Token 数|
|created_at|TIMESTAMP|创建时间|

## 5.8 Trace Span 表：ai_trace_span

|字段|类型|说明|
|---|---|---|
|id|BIGINT|主键|
|trace_id|VARCHAR|链路 ID|
|span_id|VARCHAR|节点 ID|
|parent_span_id|VARCHAR|父节点 ID|
|span_type|VARCHAR|节点类型|
|status|VARCHAR|执行状态|
|duration|BIGINT|耗时|
|error_message|TEXT|错误信息|
|input_summary|TEXT|输入摘要|
|output_summary|TEXT|输出摘要|
|created_at|TIMESTAMP|创建时间|

## 5.9 Evidence 表：ai_evidence

|字段|类型|说明|
|---|---|---|
|id|BIGINT|主键|
|trace_id|VARCHAR|Trace ID|
|document_id|BIGINT|文档 ID|
|chunk_id|BIGINT|Chunk ID|
|document_version|INTEGER|文档版本|
|retrieval_type|VARCHAR|检索类型|
|retrieval_score|DECIMAL|检索分数|
|rerank_score|DECIMAL|重排分数|
|created_at|TIMESTAMP|创建时间|

注意：

以上是初步业务模型。正式开发时需要补充索引、唯一约束、租户隔离、逻辑删除、审计字段和并发更新控制。

Qdrant 中的向量数据需要通过明确的 Point ID 与 PostgreSQL 中的 Chunk 建立关联。

---

# 六、核心 API 设计

接口统一采用 `/api/v1` 前缀。

## 6.1 知识库管理

|请求方式|接口|功能|
|---|---|---|
|POST|/api/v1/knowledge-bases|创建知识库|
|GET|/api/v1/knowledge-bases|查询知识库列表|
|GET|/api/v1/knowledge-bases/{id}|获取知识库详情|
|PUT|/api/v1/knowledge-bases/{id}|修改知识库|
|DELETE|/api/v1/knowledge-bases/{id}|删除知识库|

## 6.2 文档管理

|请求方式|接口|功能|
|---|---|---|
|POST|/api/v1/documents/upload|上传文档|
|GET|/api/v1/documents|文档列表|
|GET|/api/v1/documents/{id}|文档详情|
|GET|/api/v1/documents/{id}/status|解析状态|
|POST|/api/v1/documents/{id}/reprocess|重新处理|
|DELETE|/api/v1/documents/{id}|删除文档|

## 6.3 AI 对话

|请求方式|接口|功能|
|---|---|---|
|POST|/api/v1/chat/completions|普通问答|
|POST|/api/v1/chat/stream|SSE 流式问答|
|GET|/api/v1/conversations|会话列表|
|GET|/api/v1/conversations/{id}/messages|历史消息|
|DELETE|/api/v1/conversations/{id}|删除会话|

请求示例：

{  
"conversationId": "conv_001",  
"knowledgeBaseIds": [1, 2],  
"message": "之前订单模块的数据库连接问题是怎么解决的？"  
}

## 6.4 Agent 追踪

|请求方式|接口|功能|
|---|---|---|
|GET|/api/v1/traces|Trace 列表|
|GET|/api/v1/traces/{traceId}|Trace 详情|
|GET|/api/v1/traces/{traceId}/spans|执行节点|
|GET|/api/v1/traces/{traceId}/evidences|检索证据|

## 6.5 长期记忆

|请求方式|接口|功能|
|---|---|---|
|GET|/api/v1/memories|记忆列表|
|POST|/api/v1/memories|新增记忆|
|PUT|/api/v1/memories/{id}|更新记忆|
|DELETE|/api/v1/memories/{id}|删除记忆|

所有接口需要统一响应格式、异常码和参数校验规则。

---

# 七、前端页面设计

采用 Vue3 + TypeScript + Element Plus。

第一版需要实现以下页面：

## 7.1 登录页面

- 用户登录
    
- 登录状态维护
    
- 路由鉴权
    

## 7.2 系统首页

展示：

- 知识库数量
    
- 文档数量
    
- 问答次数
    
- Agent 执行次数
    
- 最近执行记录
    

## 7.3 知识库管理

- 知识库列表
    
- 创建知识库
    
- 编辑知识库
    
- 删除知识库
    
- 知识库详情
    

## 7.4 文档管理

- 文档上传
    
- 文档列表
    
- 文档解析状态
    
- 文档版本
    
- 文档删除
    

## 7.5 AI 对话页面

实现：

- 多轮对话
    
- SSE 流式输出
    
- Markdown 渲染
    
- 代码块展示
    
- 知识库选择
    
- 引用证据展示
    
- 会话历史
    

## 7.6 Agent 执行监控

展示：

- Trace ID
    
- 任务类型
    
- Agent 执行步骤
    
- 工具调用记录
    
- RAG 检索结果
    
- Reranker 排序结果
    
- LLM 调用耗时
    
- Token 使用量
    
- 执行失败原因
    

---

# 八、项目目录结构

建议采用 Maven 多模块架构。

team-mind/  
├── pom.xml  
├── README.md  
├── DEVELOPMENT.md  
├── docker-compose.yml  
│  
├── team-mind-common/  
│ └── 通用工具、异常、响应对象  
│  
├── team-mind-system/  
│ └── 用户、权限、系统管理  
│  
├── team-mind-knowledge/  
│ └── 知识库、文档、分块、入库  
│  
├── team-mind-ai/  
│ ├── agent/  
│ ├── rag/  
│ ├── memory/  
│ ├── model/  
│ ├── prompt/  
│ └── tool/  
│  
├── team-mind-observability/  
│ └── Trace、Span、Evidence  
│  
├── team-mind-web/  
│ └── Controller、接口入口、启动类  
│  
├── team-mind-evaluation/  
│ └── Agent 和 RAG 评测  
│  
└── team-mind-ui/  
└── Vue3 前端项目

实际模块依赖关系需要通过 Maven 明确约束，禁止循环依赖。

---

# 九、非功能性要求

## 9.1 安全性

1. API Key 通过环境变量或配置中心管理。
    
2. 密码必须使用安全的单向哈希算法保存。
    
3. 知识库必须实现访问权限校验。
    
4. 不允许通过用户输入直接执行 SQL。
    
5. 文件上传需要进行类型和大小校验。
    
6. 防止 Prompt Injection 导致越权工具调用。
    
7. Agent 工具执行必须具备权限校验。
    
8. 日志不得泄漏敏感信息。
    

## 9.2 稳定性

1. LLM 调用支持超时控制。
    
2. 外部模型调用支持有限次数重试。
    
3. 防止 Agent 无限循环。
    
4. 文档处理失败可以重新执行。
    
5. 文档入库支持幂等。
    
6. Redis、PostgreSQL、Qdrant 异常需要有明确处理策略。
    

## 9.3 可维护性

1. 核心模块必须有清晰的接口定义。
    
2. 复杂业务逻辑必须拆分。
    
3. 重要业务逻辑需要添加注释。
    
4. Prompt 统一管理。
    
5. 模型参数配置化。
    
6. 核心模块提供单元测试。
    
7. 关键业务链路提供集成测试。
    

---

# 十、开发阶段规划

必须按照以下顺序分阶段实现。

不要一次性生成所有模块。

## Phase 1：项目基础架构

任务：

- 创建 Maven 多模块工程。
    
- 创建 Spring Boot 主启动模块。
    
- 配置 PostgreSQL。
    
- 配置 Redis。
    
- 配置 Qdrant。
    
- 配置统一异常处理。
    
- 配置统一响应对象。
    
- 配置 SpringDoc。
    
- 编写 Docker Compose。
    
- 完成数据库初始化脚本。
    

验收标准：

- 项目能够成功启动。
    
- PostgreSQL、Redis、Qdrant 均能正常连接。
    
- API 文档能够访问。
    
- 数据库初始化脚本可以执行。
    

## Phase 2：基础 RAG

任务：

- 实现文档上传。
    
- 实现 PDF、DOCX、TXT、Markdown 解析。
    
- 实现文本分块。
    
- 实现 Embedding。
    
- 实现 Qdrant 向量入库。
    
- 实现向量检索。
    
- 实现基于检索结果的 LLM 问答。
    

验收标准：

上传 PDF 后，可以完成解析、分块、向量化和入库。

用户提问后，可以根据文档内容生成答案。

答案可以返回原始文档和 Chunk 引用。

Phase 2 实现与自动化验证记录见 `docs/phase2-verification.md`，当前架构见 `docs/architecture.md`。真实 PostgreSQL、Qdrant 和 Qwen 的最终验收仍需按记录在本机执行。

## Phase 3：高级 RAG

任务：

- 实现 BM25。
    
- 实现 Dense + BM25 并行检索。
    
- 实现 RRF 融合。
    
- 实现 BGE-Reranker。
    
- 实现文档版本过滤。
    
- 实现 Evidence Board。
    

验收标准：

能够分别测试不同检索策略。

能够展示每种检索策略的结果和排名。

最终答案引用必须能够追溯到真实文档和原始 Chunk。

## Phase 4：Agent 系统

任务：

- 实现规则预处理。
    
- 实现 LLM 结构化任务理解。
    
- 实现任务分类。
    
- 实现 Tool Registry。
    
- 实现 Function Calling。
    
- 实现 ReAct 执行循环。
    
- 实现工具异常处理。
    
- 实现执行次数限制。
    
- 实现 SSE 流式回答。
    

验收标准：

能够根据不同问题类型选择不同工具。

能够连续执行多个工具。

能够正确处理工具失败、超时和终止条件。

## Phase 5：记忆系统

任务：

- 实现短期记忆。
    
- 实现 Redis 会话管理。
    
- 实现上下文裁剪。
    
- 实现增量摘要。
    
- 实现长期记忆提取。
    
- 实现长期记忆持久化。
    
- 实现记忆检索。
    
- 实现记忆更新和失效。
    

验收标准：

连续对话可以正确理解上下文。

结束会话后，经过确认的长期记忆能够在新会话中使用。

长期记忆可以查看来源、更新和删除。

## Phase 6：可观测性

任务：

- 实现 Trace ID。
    
- 实现 Span。
    
- 实现 Agent 节点记录。
    
- 实现工具调用记录。
    
- 实现 RAG 检索记录。
    
- 实现 Token 统计。
    
- 实现耗时统计。
    
- 实现异常追踪。
    
- 实现前端 Trace 可视化。
    

验收标准：

输入一个 Trace ID，可以查看本次请求的完整执行过程。

可以定位具体失败节点。

可以统计单次请求的耗时和 Token 消耗。

## Phase 7：评测体系

任务：

- 构建任务识别数据集。
    
- 构建 RAG 检索数据集。
    
- 构建记忆测试数据集。
    
- 实现 Recall@K。
    
- 实现 MRR。
    
- 实现 NDCG。
    
- 实现答案和引用质量评估。
    
- 输出评测报告。
    

验收标准：

可以对不同 RAG 检索方案进行对比。

可以重复执行同一套评测数据集。

评测结果可以保存和追溯。

## Phase 8：前端完善和整体联调

任务：

- 完成知识库管理。
    
- 完成文档管理。
    
- 完成 AI 对话。
    
- 完成证据引用展示。
    
- 完成 Agent 执行监控。
    
- 完成异常提示。
    
- 完成前后端整体联调。
    

验收标准：

能够完整演示从上传文档到 Agent 检索、回答、引用和执行追踪的整个过程。

---

# 十一、测试要求

测试分为四类。

## 11.1 单元测试

重点测试：

- 文档分块。
    
- RRF 算法。
    
- 检索结果合并。
    
- 任务分类结果校验。
    
- Agent 执行终止条件。
    
- 记忆更新逻辑。
    
- Evidence 引用校验。
    

## 11.2 集成测试

重点测试：

- PostgreSQL 数据访问。
    
- Redis 会话管理。
    
- Qdrant 向量入库和查询。
    
- Embedding 模型调用。
    
- Reranker 模型调用。
    
- Agent 工具调用。
    

## 11.3 异常测试

必须覆盖：

- LLM 接口超时。
    
- LLM 返回非法 JSON。
    
- Qdrant 不可用。
    
- Redis 不可用。
    
- 文档解析失败。
    
- Embedding 失败。
    
- Agent 工具调用失败。
    
- Agent 超过最大执行次数。
    
- 无相关知识库证据。
    
- 用户无知识库访问权限。
    

## 11.4 效果测试

使用固定测试数据集，对任务识别、检索和最终答案进行效果评估。

所有准确率、召回率和提升比例都必须通过实际测试计算。

---

# 十二、AI 编程工具开发规范

本章节非常重要，Codex 和 Claude Code 必须遵循。

## 12.1 开发原则

1. 先阅读项目开发文档，再开始编写代码。
    
2. 先检查现有代码，不允许在不了解项目结构的情况下大规模修改。
    
3. 必须遵循现有项目的技术栈和分层架构。
    
4. 优先使用成熟、稳定的依赖。
    
5. 不允许为了简单而将核心逻辑全部写在一个类中。
    
6. 不允许使用大量硬编码。
    
7. 不允许虚构第三方 API。
    
8. 不允许编写无法编译的示例代码冒充完整实现。
    
9. 不允许跳过必要的异常处理。
    
10. 不允许通过删除测试来解决测试失败。
    

## 12.2 代码质量要求

Java 代码要求：

- 遵循 Java 21 编码规范。
    
- 类和方法命名清晰。
    
- 合理使用接口和实现类。
    
- 避免过度设计。
    
- 合理使用 DTO、VO、Entity。
    
- 业务逻辑不能全部堆积在 Service。
    
- 核心算法需要添加必要注释。
    
- 对外部调用设置超时和异常处理。
    
- 避免不必要的全局静态状态。
    

## 12.3 AI 模块开发要求

Agent、RAG、Memory 必须保持独立。

不能将以下功能混合在一个类中：

- Agent 任务理解。
    
- Agent 执行循环。
    
- 工具调用。
    
- 文档检索。
    
- 记忆管理。
    
- LLM 模型调用。
    

Prompt 需要统一管理。

模型调用需要抽象接口。

所有模型请求需要考虑超时、异常、Token 使用量和日志记录。

## 12.4 开发流程

每个功能按照以下流程执行：

第一步：阅读相关模块代码。

第二步：分析需求和影响范围。

第三步：输出实现方案。

第四步：明确修改和新增文件。

第五步：实现代码。

第六步：编译项目。

第七步：执行相关测试。

第八步：检查代码质量。

第九步：更新开发文档。

第十步：汇报完成情况和遗留问题。

每次只实现一个明确的功能模块，不要未经确认就同时重构多个模块。

## 12.5 每次开发完成后的汇报格式

AI 编程工具必须输出：

1. 本次完成的功能。
    
2. 修改的文件。
    
3. 新增的类和接口。
    
4. 数据库变更。
    
5. 核心实现逻辑。
    
6. 编译和测试结果。
    
7. 如何启动和验证。
    
8. 尚未解决的问题。
    
9. 下一步建议。
    

---

# 十三、第一阶段启动任务

项目初始化后，首先只执行 Phase 1。

请不要直接开发 Agent、RAG 和记忆模块。

第一轮任务：

1. 创建 Maven 多模块工程。
    
2. 建立模块之间的依赖关系。
    
3. 配置 Spring Boot 3.x 和 Java 21。
    
4. 配置 PostgreSQL、Redis、Qdrant。
    
5. 编写 Docker Compose。
    
6. 配置 application.yml。
    
7. 建立统一响应对象。
    
8. 建立全局异常处理。
    
9. 配置 MyBatis-Plus。
    
10. 配置 SpringDoc。
    
11. 编写数据库初始化脚本。
    
12. 编写基础集成测试。
    
13. 确保整个工程能够成功编译和启动。
    

完成后先停止开发，等待人工验收。

Phase 1 工程已于 2026-11-14 初始化。架构说明见 `docs/architecture.md`，启动步骤见 `README.md`，实际验证范围及待本机 Docker 验收项见 `docs/phase1-verification.md`。

---

# 十四、项目最终验收标准

项目最终必须能够完成以下完整流程：

1. 用户登录系统。
    
2. 创建一个研发知识库。
    
3. 上传 PDF、DOCX 等技术文档。
    
4. 后端自动解析文档并完成分块。
    
5. 文档经过 Embedding 后存入 Qdrant。
    
6. 用户提出研发技术问题。
    
7. Agent 识别问题类型。
    
8. Agent 根据任务决定是否调用工具。
    
9. RAG 使用 Dense + BM25 进行混合检索。
    
10. 通过 RRF 融合结果。
    
11. 通过 BGE-Reranker 进行重排序。
    
12. Agent 根据证据生成最终答案。
    
13. 返回答案及原始文档引用。
    
14. 保存本次会话上下文。
    
15. 提取并管理长期记忆。
    
16. 生成完整 Trace ID。
    
17. 可以查看 Agent 的执行过程。
    
18. 可以定位执行失败的节点。
    
19. 可以运行评测数据集并生成评测报告。
    

只有完成核心流程并通过测试，才算完成第一版项目。

---

# 十五、项目开发约定

本项目采用持续迭代的方式开发。

优先保证：

**正确性 > 可维护性 > 可观测性 > 性能优化 > 功能数量。**

第一版不追求微服务拆分，不引入不必要的中间件。

先采用模块化单体架构，保证核心功能完整，后续再根据业务规模考虑服务拆分。

所有重要技术选型需要有明确理由。

所有核心算法需要能够解释原理。

所有量化指标必须通过实际测试获得。

项目最终目标不仅是完成一个可以运行的 AI 应用，还要能够清楚讲解整个系统的架构、技术选型、核心算法、工程实现和性能优化过程。

**开发文档结束。**