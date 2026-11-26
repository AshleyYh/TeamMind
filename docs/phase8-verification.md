# Phase 8 验收记录

## 已实现范围

Phase 8 完成了 Vue 3 + TypeScript + Element Plus 控制台和前后端联调：

- 开发模式登录：保存 `ownerId` 和显示名称到 localStorage，路由守卫。
- 工作台：知识库、文档、会话、记忆、Trace、评测数量，失败执行提示和最近 Trace。
- 知识库：列表、创建、编辑、删除，进入文档管理。
- 文档：知识库选择、PDF/DOCX/TXT/Markdown 上传、状态自动轮询、版本、失败原因、重处理、删除。
- AI 对话：多轮会话、知识库多选、POST SSE 流式输出、Markdown/代码块、引用证据、Agent 执行步骤、Token、Trace 跳转、会话历史、长期记忆候选提取与确认。
- 执行监控：Trace 列表、详情、Span 节点、任务/工具/RAG/模型节点、耗时、输入/输出 Token、失败原因、Evidence。
- 长期记忆：状态/类型筛选、关键词检索、新增、编辑、确认、失效、删除、来源 Trace。
- 评测中心：任务识别、RAG 多策略、长期记忆评测，指标卡片、逐用例结果、历史运行、Markdown 报告。
- 新增后端 `GET /api/v1/dashboard/stats` 聚合接口。
- Vite 开发代理 `/api` 到后端 8080，`vue-tsc` 类型检查和 `vite build` 生产构建。

## 前端页面

| 路由 | 页面 |
| --- | --- |
| `/login` | 开发模式登录 |
| `/dashboard` | 工作台 |
| `/knowledge-bases` | 知识库管理 |
| `/documents` | 文档上传与状态 |
| `/chat` | AI 对话、SSE、证据与执行步骤 |
| `/traces` | Agent/RAG 执行监控 |
| `/memories` | 长期记忆管理 |
| `/evaluation` | 评测中心 |

## 关键实现

### SSE

`EventSource` 不支持 POST，因此 `streamAgentChat` 使用 `fetch` + `ReadableStream`：

```text
POST /api/v1/chat/stream
  -> 读取 text/event-stream
  -> 按空行切分事件块
  -> 解析 event:/data:
  -> start / task / step / answer_delta / done / error
```

对话页在 `answer_delta` 追加 Markdown，`done` 覆盖为服务端最终答案，并展示引用、终止原因、警告和 Token。停止生成使用 `AbortController`。

### 证据展示

- 回答中的 `[n]` 与右侧 Evidence 面板一一对应。
- 证据卡展示文档名、版本、页码、分数、重排分数和原文。
- 点击 Reply 中的 citation chip 会切换证据面板。
- Trace 页的 Evidence 表显示是否被实际引用。

### Agent 监控

Trace 详情展示：

- Trace 状态、策略、会话、总耗时、输入/输出/总 Token。
- 每个 Span 的 `spanType`、状态、耗时、Token、输入/输出摘要、错误类型和错误信息。
- Evidence 引用状态。
- 失败 Trace 的 `errorMessage` 和失败 Span。

### 开发模式登录边界

登录页只保存 `ownerId`，没有密码、Token、服务端会话或权限系统。local profile 默认初始化 ownerId=1 的占位用户；其他 ownerId 需要数据库中存在对应 `sys_user`。页面明确提示不能部署到公网。

## 自动化验证结果

### 后端

2026-11-26 使用 OpenJDK 21.0.13.1 和 Maven Wrapper 3.9.11，在隔离源码副本执行：

```bash
./mvnw clean package
```

结果成功，8 个 Maven 模块全部打包，默认单元与上下文测试共 **162 项，全部通过**。新增 Dashboard 统计服务与接口测试 3 项，Phase 1-7 既有测试 159 项。

### 前端

```bash
cd team-mind-ui
npm install --registry=https://registry.npmmirror.com
npm run typecheck
npm run build
```

`vue-tsc --noEmit` 通过，`vite build` 成功生成 `dist`。构建产物包含按路由拆分的页面 chunk；Element Plus 全量引入使主 vendor chunk 较大，后续可优化。

前端暂无单元测试或端到端自动化测试。

## 本机联调步骤

1. 启动基础设施：PostgreSQL、Redis、Qdrant。
2. 配置 `QWEN_API_KEY`，启动后端：

```bash
java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local
```

3. 启动前端：

```bash
cd team-mind-ui
npm install --registry=https://registry.npmmirror.com
npm run dev
```

4. 打开 <http://localhost:5173>，使用 ownerId=1 进入。
5. 创建知识库，上传 PDF/DOCX/TXT/Markdown，等待状态变为 `COMPLETED`。
6. 进入 AI 对话，选择知识库，提问并观察 SSE、引用证据、执行步骤和 Trace ID。
7. 点击 Trace 查看 Span、Token、耗时和失败节点；点击引用查看原文。
8. 在对话页提取长期记忆候选，确认后开启新会话，验证 `historyMemorySearch`。
9. 进入评测中心运行 RAG 多策略评测，查看指标和 Markdown 报告。

## 尚待真实环境验收与已知限制

- 真实登录认证、密码、Token、权限和路由鉴权尚未实现；当前 ownerId 入口只能用于开发/受控环境。
- 前端没有单元测试和 E2E 自动化测试，`vue-tsc` 和构建通过不等同于所有交互已在真实浏览器验证。
- 真实 Qwen、Reranker、PostgreSQL/Redis/Qdrant 全链路联调需要在 Docker 或本机基础设施上执行。
- Element Plus 全量引入使主包较大，SSE 页面已经按路由拆分；后续可使用 unplugin 按需引入和 manualChunks。
- 移动端做了基础响应式，复杂交互（对话三栏、Trace 时间线）在窄屏上仍需人工检查。
- 生产静态部署需要配置 `/api` 反向代理，并关闭 SSE 代理缓冲、设置足够空闲超时。
- 评测数据集是小规模示例，真实效果仍需按企业知识库调整。
