# TeamMind 当前架构说明（Phase 2）

## 目标与边界

Phase 2 在 Phase 1 的模块化单体上打通基础 RAG：知识库管理、文档上传与解析、异步分块和向量入库、Dense 向量检索、Qwen 问答以及可追溯引用。

本阶段不包含 BM25、RRF、Reranker、Evidence Board、Agent、记忆、流式输出、登录认证和前端页面。对应能力分别属于后续阶段，不能把当前 `ownerId` 参数当作权限系统。

## 模块与依赖

```text
team-mind-web
  -> team-mind-ai
       -> team-mind-system
       -> team-mind-knowledge -> team-mind-common
       -> team-mind-observability -> team-mind-common
  -> team-mind-evaluation -> team-mind-ai
```

`knowledge` 负责知识库、文档、解析、分块、向量存储和检索，不依赖 Qwen SDK。`ai` 实现 Embedding 与对话模型网关，并编排 RAG 回答。`observability` 负责 Qdrant 健康指标，后续承载 Trace 与 Evidence。`web` 只包含 Controller、启动类和应用配置。

`EmbeddingGateway`、`VectorStoreGateway` 和 `ChatCompletionGateway` 都是端口接口，业务层不直接依赖 Qwen 或 Qdrant 的实现类。`team-mind-ui` 是独立前端工作区，不属于 Maven reactor。

## 文档入库链路

```text
上传请求
  -> 校验 ownerId、知识库、文件大小、文件名和扩展名
  -> 保存到本地文档目录
  -> PostgreSQL 写入文档元数据（PENDING）
  -> 事务提交后调度异步任务
       -> 条件更新为 PROCESSING，防止同一文档并发处理
       -> 清理该文档旧分块与旧向量
       -> PDFBox/Tika 解析文本
       -> 按配置分块并保留 PDF 页码
       -> 分批调用 Embedding
       -> Qdrant upsert（确定性 Point ID）
       -> PostgreSQL 写入分块（含 vector_id）
       -> 标记 COMPLETED
```

任何步骤失败都会尝试清理该文档向量并标记 `FAILED`；业务错误原因最多保存 500 个字符，未知异常只写日志，避免内部信息通过接口泄漏。重新处理走相同链路，因此可以清理半成功产物。

为支持版本更新，同一知识库内有效文档名称唯一；重传同名文件时文档版本加一。检索时会校验分块版本必须等于文档当前版本，旧版本分块不会作为答案证据。

## 检索与回答链路

```text
用户问题
  -> 校验知识和知识库归属
  -> Qwen Embedding
  -> Qdrant 按知识库过滤的 Dense 检索
  -> PostgreSQL 读取 Chunk 与当前有效文档
  -> 丢弃已删除、失败处理、旧版本或不属于所选知识库的结果
  -> 无证据则直接拒答且不调用对话模型
  -> 构建带编号、分隔标记的证据上下文
  -> Qwen Chat
  -> 校验回答中的引用编号范围
  -> 返回答案与真实 Chunk 引用
```

引用由服务端根据检索结果生成，模型不能提交文档 ID 或路径。模型返回不存在的引用编号时整次回答被拒绝。文档名称会做属性转义，证据使用显式分隔标签，系统提示要求忽略证据中的越权指令；这只能降低 Prompt Injection 风险，仍需要后续 Agent 工具权限层。

## 数据一致性与并发

Qdrant Point ID 来自 `documentId + version + chunkIndex` 的确定性 UUID，重试覆盖同一点而不是追加。删除文档先删除该文档全部向量，再删除数据库分块并逻辑删除文档。若向量库不可用，删除会失败而不会留下“数据库已删除但向量仍可检索”的状态。

上传事务只在提交后触发异步处理，避免任务读不到数据库记录。文档状态用条件更新认领任务，同名文档正在处理时拒绝再次上传。文档处理有独立有界线程池，避免上传高峰无限堆积任务。

Qdrant 集合在首次入库时创建，并校验已有集合的向量维度与配置一致。集合尚未创建时检索返回空结果；删除不存在的集合按幂等成功处理。向量库异常统一映射为 `50301`，不会向客户端暴露远程报文。

## 数据库迁移

Flyway 仍是唯一自动迁移入口。`V2__add_phase2_constraints.sql` 删除不适合同名版本更新的旧约束，增加有效文档名称与有效知识库名称的部分唯一索引。已应用迁移不得修改，后续变更继续新增迁移文件。

`knowledge_chunk.vector_id` 关联 PostgreSQL 分块与 Qdrant Point。旧版本 Chunk 在重新处理时清理，文档删除时同步清理。当前只保存文档当前版本的文件与元数据，不保留可回滚的历史版本快照。

## 配置、安全与已知限制

模型地址、模型名、维度、超时、文件上限、分块参数、批大小和检索参数均可通过环境变量覆盖。QWEN_API_KEY 不写入仓库；`.env` 仅供 Docker Compose，Java 进程需要从运行环境注入密钥。上传文件目录 `data/` 已被 Git 忽略，接口不返回服务器文件路径。

Embedding 与问答客户端使用 OpenAI 兼容协议。除 DashScope 云端 Qwen 外，可通过 `ollama` profile 接入本地 `qwen3.5:9b` 和 `qwen3-embedding:0.6b`，并使用独立 Qdrant 集合隔离本地与云端向量空间；部署步骤见 `docs/local-models.md`。

当前最大限制是没有身份认证：`ownerId` 由客户端显式传入，只能保证知识库归属一致性，不能阻止客户端伪造其他用户 ID。进入正式使用前必须接入登录与授权，并把用户身份从请求参数改为安全上下文。当前也未实现独立权限关联表、文档历史版本回滚、对象存储、病毒扫描、限流和真实 Qwen 计费监控。
