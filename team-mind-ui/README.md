# team-mind-ui

TeamMind 企业研发知识库 Agent 控制台。Vue 3 + TypeScript + Vite + Element Plus。

## 页面

- 开发模式登录：选择 `ownerId` 和显示名称，保存到 localStorage。
- 工作台：知识库、文档、会话、记忆、Trace、评测数量和最近执行。
- 知识库：创建、编辑、删除、进入文档管理。
- 文档：上传 PDF/DOCX/TXT/Markdown、状态轮询、版本、重处理、删除。
- AI 对话：多轮会话、知识库多选、SSE 流式回答、Markdown/代码块、引用证据、Agent 执行步骤、Token、Trace 跳转、长期记忆候选提取。
- 执行监控：Trace 列表、Span 时间线、工具调用、RAG 检索、Token/耗时、失败节点、Evidence。
- 长期记忆：状态/类型筛选、关键词检索、新增、编辑、确认、失效、删除、来源 Trace。
- 评测中心：任务识别、RAG 多策略、长期记忆评测，指标卡片、逐用例结果、历史运行和 Markdown 报告。

## 开发

```bash
cd team-mind-ui
npm install --registry=https://registry.npmmirror.com
npm run dev
```

开发服务器默认 `http://localhost:5173`，`/api` 代理到 `http://localhost:8080`。先启动后端。

## 构建

```bash
npm run typecheck
npm run build
```

`dist` 是静态产物，可以交给 Nginx 或其他静态服务器。生产环境需要把 `/api` 反向代理到后端，并关闭 SSE 代理缓冲。

## 说明

当前没有真实登录认证，登录页只是开发期 `ownerId` 入口。不要部署到公网。后端所有接口仍以 `ownerId` 做资源隔离。
