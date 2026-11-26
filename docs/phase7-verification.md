# Phase 7 验收记录

## 已实现范围

Phase 7 在 Agent、RAG、记忆和 Trace 之上实现评测体系：

- 任务识别数据集：问题、期望任务类型、期望工具。
- RAG 检索数据集：查询、相关文档名、相关关键词、答案关键词。
- 长期记忆数据集：查询、期望记忆关键词。
- 数据集版本和 SHA-256，运行记录可追溯。
- Recall@K、MRR、NDCG@K。
- 任务分类准确率、工具命中率。
- 引用精确率、引用召回率、答案关键词覆盖率。
- 多策略 RAG 对比：DENSE、BM25、HYBRID、HYBRID_RERANK。
- 评测运行和逐用例结果持久化。
- Markdown 评测报告保存和下载。
- 评测运行列表、详情和按 runId 追溯。

## 数据集

内置资源位于 `team-mind-evaluation/src/main/resources/evaluation/`：

| 文件 | 内容 |
| --- | --- |
| `task-classification.json` | 5 个任务识别用例，覆盖问候、历史、文档检索、知识问答和普通对话 |
| `rag-retrieval.json` | 3 个 RAG 用例，使用 `order-design.pdf`、`payment-design.pdf` 等示例文档名 |
| `memory-retrieval.json` | 3 个记忆检索用例，期望关键词为连接数、HikariCP 和简洁 |

数据集名称使用白名单，单数据集最多 200 个用例。示例数据需要按真实知识库调整，不能直接作为生产效果结论。

## 指标

- `Recall@K`：前 K 个结果命中的相关项数 / 相关项总数。
- `MRR`：第一个相关结果的排名倒数。
- `NDCG@K`：二值相关性 DCG / IDCG。
- `accuracy`：任务类型分类正确率。
- `toolSelectionAccuracy`：期望工具被实际建议工具覆盖的比例。
- `citationPrecision`：实际引用文档中相关文档占比。
- `citationRecall`：相关文档被实际引用的比例。
- `keywordCoverage`：答案包含期望关键词的比例。

RAG 检索按文档名去重计算文档级指标；引用指标只统计答案中实际出现的 `[n]` 对应的文档。记忆评测按期望关键词判断记忆是否命中。

## 新增接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/evaluation/datasets` | 查询内置数据集、版本、用例数和 hash |
| POST | `/api/v1/evaluation/tasks` | 任务识别和工具选择评测 |
| POST | `/api/v1/evaluation/rag` | 多策略 RAG 检索评测，可选答案/引用评测 |
| POST | `/api/v1/evaluation/memories` | 长期记忆检索评测 |
| GET | `/api/v1/evaluation/runs` | 查询历史运行 |
| GET | `/api/v1/evaluation/runs/{runId}` | 查询运行详情和逐用例结果 |
| GET | `/api/v1/evaluation/runs/{runId}/report` | 下载 Markdown 报告 |

## 数据模型

新增 `V6__add_phase7_evaluation.sql`：

- `ai_evaluation_run`：runId、归属、数据集类型/名称/hash、知识库、策略、状态、用例数、聚合指标、Markdown 报告、错误、开始/结束时间。
- `ai_evaluation_case`：runId、用例 ID、查询、期望 JSON、实际 JSON、指标 JSON、成功标记、耗时、错误。
- 运行和用例都带查询索引；用例通过 `run_id` 关联运行。

运行 ID 为 32 位十六进制 UUID，列表和详情都校验 `ownerId`。

## 核心实现逻辑

```text
加载数据集（白名单 + SHA-256）
  -> 创建 RUNNING 运行记录
  -> 逐用例执行
       任务：TaskUnderstandingService
       RAG：KnowledgeSearchService 多策略检索，可选 RagAnswerService 生成答案
       记忆：MemoryService.search
  -> 计算用例指标
  -> 聚合平均指标
  -> 保存逐用例结果
  -> 渲染 Markdown 报告
  -> 运行标记 SUCCESS / FAILED
```

RAG 答案评测默认关闭；开启后每个策略/用例会额外调用一次对话模型，并计算引用精确率、引用召回率和答案关键词覆盖率。评测运行同步执行，适合受控环境的小规模对比。

## 自动化验证结果

2026-11-26 使用临时隔离的 OpenJDK 21.0.13.1 和项目 Maven Wrapper 3.9.11，在不含历史构建产物的源码副本中执行：

```bash
./mvnw clean package
```

结果成功，8 个 Maven 模块全部打包，默认单元与上下文测试共 **159 项，全部通过**。

| 测试范围 | 数量 | 覆盖内容 |
| --- | ---: | --- |
| 指标计算 | 4 | 全命中、MRR、部分召回、空结果、平均值 |
| 数据集加载 | 2 | 三类数据集、版本/hash、白名单拒绝 |
| 评测运行持久化 | 4 | 创建运行、记录用例、成功/失败收尾、归属校验、用例查询 |
| 任务评测 | 1 | 任务类型和工具命中率 |
| RAG 评测 | 2 | 多策略召回/MRR/NDCG、答案引用和关键词指标 |
| 记忆评测 | 1 | ACTIVE 记忆关键词命中 |
| 评测 Web 接口 | 4 | 数据集、任务评测、RAG 参数校验、运行详情、Markdown 报告、非法 runId |
| 既有 Phase 1-6 测试 | 141 | 原有 RAG、Agent、记忆、Trace、Evidence、Web 和上下文测试 |

外部 Qwen、Reranker、Redis 和 PostgreSQL 在默认测试中由确定性替身或 Mock 替代；这不代表真实模型效果或真实基础设施集成验收。

## 本机验收步骤

启动 PostgreSQL、Redis、Qdrant，配置 `QWEN_API_KEY`，完成文档入库。先查看数据集：

```bash
curl 'http://localhost:8080/api/v1/evaluation/datasets'
```

执行不调用对话模型的多策略 RAG 评测：

```bash
curl -X POST http://localhost:8080/api/v1/evaluation/rag \
  -H 'Content-Type: application/json' \
  -d '{"ownerId":1,"knowledgeBaseIds":[1],
       "strategies":["DENSE","BM25","HYBRID","HYBRID_RERANK"],
       "topK":5,"dataset":"rag-retrieval","evaluateAnswers":false}'
```

检查响应 `metrics.strategies` 中四种策略的 `recallAtK`、`mrr`、`ndcgAtK`，并对比结果。示例数据集使用 `order-design.pdf` 和 `payment-design.pdf`，如本地文档名不同，请先修改对应 JSON。

可选答案/引用评测：

```bash
curl -X POST http://localhost:8080/api/v1/evaluation/rag \
  -H 'Content-Type: application/json' \
  -d '{"ownerId":1,"knowledgeBaseIds":[1],"strategies":["HYBRID_RERANK"],
       "topK":5,"dataset":"rag-retrieval","evaluateAnswers":true}'
```

这会调用 Qwen 生成答案，返回引用精确率、引用召回率和答案关键词覆盖率。

任务识别评测：

```bash
curl -X POST http://localhost:8080/api/v1/evaluation/tasks \
  -H 'Content-Type: application/json' \
  -d '{"ownerId":1,"dataset":"task-classification","knowledgeBaseIds":[1]}'
```

长期记忆评测（需要先准备 ACTIVE 记忆）：

```bash
curl -X POST http://localhost:8080/api/v1/evaluation/memories \
  -H 'Content-Type: application/json' \
  -d '{"ownerId":1,"topK":5,"dataset":"memory-retrieval"}'
```

查看运行和报告：

```bash
curl 'http://localhost:8080/api/v1/evaluation/runs?ownerId=1&limit=20'
curl 'http://localhost:8080/api/v1/evaluation/runs/替换为runId?ownerId=1'
curl 'http://localhost:8080/api/v1/evaluation/runs/替换为runId/report?ownerId=1'
```

重复执行同一数据集时，比较运行记录中的 `datasetHash`，确认使用的是同一版本。

## 尚待真实环境验收与已知限制

- 内置数据集只是小规模示例，需要按真实知识库和业务问题扩充；当前准确率/召回率不能直接作为生产效果结论。
- 真实 Qwen、Reranker、PostgreSQL/Redis/Qdrant 集成尚未在 Docker 环境执行；默认测试使用 Mock/确定性替身。
- RAG 答案和引用评测需要显式开启，会消耗模型配额；评测结果依赖当时的模型、重排和知识库状态。
- 评测同步执行，大数据集可能长时间占用 HTTP 请求；没有异步任务、进度查询、统计显著性、置信区间和前端评测页面。
- 记忆评测基于当前用户已有 ACTIVE 记忆，会更新命中记忆的 `lastUsedAt`，没有独立评测沙箱。
- 任务识别评测在模型不可用时可能降级为规则分类，指标会反映降级后的结果，需要在运行元数据中结合日志判断。
- `ownerId` 仍不是身份认证，评测接口只能用于受控开发环境。
