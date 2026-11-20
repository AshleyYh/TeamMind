# Phase 3：qwen3.7-text-rerank 验收流程

本流程使用本地 Ollama 提供 Embedding 和对话，使用阿里云百炼 `qwen3.7-text-rerank` 提供重排。先直连模型验证密钥和业务空间，再启动应用验证完整 `HYBRID_RERANK` 链路。

## 1. 准备参数

从千问 AI 平台或百炼控制台复制以下信息：

- API Key：不要写入仓库、截图或对话。
- 千问 AI 平台地址；或百炼业务空间 ID（WorkspaceId）。
- 模型详情页确认 `qwen3.7-text-rerank` 已开通。

### 方案一：千问 AI 平台 `maas.qianwenaiapi.com`

千问 AI 平台的重排使用 DashScope 原生协议：

```text
https://maas.qianwenaiapi.com/api/v1/services/rerank/text-rerank/text-rerank
```

应用配置中的 `RERANK_BASE_URL` 设置为：

```text
https://maas.qianwenaiapi.com/api/v1
```

不要使用它的 OpenAI 兼容入口：

```text
https://maas.qianwenaiapi.com/compatible-mode/v1
```

`/compatible-mode/v1` 用于 Chat/Embedding，不是当前代码使用的重排路径。

### 方案二：阿里云百炼业务空间

华北 2（北京）的 DashScope 原生地址格式为：

```text
https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
```

应用配置中的 `RERANK_BASE_URL` 只填写到：

```text
https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1
```

不要包含 `/services/rerank/...`。

当前实现使用原生 DashScope 协议。不要配置成 `/compatible-api/v1/reranks`，该地址的请求参数是扁平结构，和当前实现不同。

## 2. 直连验证模型

在终端中一次性设置变量。API Key 使用 `read -s` 避免出现在 shell 历史中：

```bash
read -s RERANK_API_KEY
export RERANK_API_KEY

# 千问 AI 平台使用这一行：
export RERANK_BASE_URL='https://maas.qianwenaiapi.com/api/v1'

# 如果使用百炼业务空间，则改为：
# export RERANK_WORKSPACE_ID='替换为业务空间ID'
# export RERANK_BASE_URL="https://${RERANK_WORKSPACE_ID}.cn-beijing.maas.aliyuncs.com/api/v1"

export RERANK_MODEL='qwen3.7-text-rerank'
```

调用模型，先确认文档 `index=1` 排名高于 `index=0`：

```bash
curl -sS -X POST \
  "${RERANK_BASE_URL}/services/rerank/text-rerank/text-rerank" \
  -H "Authorization: Bearer ${RERANK_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3.7-text-rerank",
    "input": {
      "query": "订单服务最大连接数是多少？",
      "documents": [
        "订单表使用雪花 ID 作为主键。",
        "订单服务使用 HikariCP，最大连接数为 30。",
        "前端页面使用 Vue 3。"
      ]
    },
    "parameters": {
      "top_n": 3,
      "instruct": "Given a web search query, retrieve relevant passages that answer the query."
    }
  }'
```

成功响应应包含：

```json
{
  "output": {
    "results": [
      {"index": 1, "relevance_score": 0.9},
      {"index": 0, "relevance_score": 0.1},
      {"index": 2, "relevance_score": 0.1}
    ]
  }
}
```

`relevance_score` 的具体值不重要，关键是包含最大连接数的文档 `index=1` 排名第一。

常见错误：

- `401`：API Key 错误或与业务空间不匹配。
- `404`：WorkspaceId、地域或请求路径错误。
- `400`：模型名错误，或单条 Query/Document 超过模型限制。
- `403`：模型未开通、免费额度不可用或账号无权限。

## 3. 启动应用

确认 PostgreSQL、Redis、Qdrant 和 Ollama 已启动：

```bash
cd /Users/yuhang/Projects/TeamMind

docker compose up -d --wait
curl -fsS http://localhost:11434/api/tags >/dev/null && echo 'Ollama OK'
```

重新打包，使 qwen3.7 协议适配生效：

```bash
./mvnw clean package -DskipTests
```

设置应用环境变量。`RERANK_API_KEY` 必须显式设置；Ollama profile 中的占位密钥不能用于百炼：

```bash
# 与第 2 节选择的平台保持一致；不要在 URL 中包含 /services/rerank/...
export RERANK_BASE_URL='https://maas.qianwenaiapi.com/api/v1'
export RERANK_API_KEY="${RERANK_API_KEY}"
export RERANK_MODEL='qwen3.7-text-rerank'
export RERANK_INSTRUCT='Given a web search query, retrieve relevant passages that answer the query.'
export RAG_STRATEGY='HYBRID_RERANK'
export RAG_RERANK_CANDIDATE_SIZE='10'

java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local,ollama
```

`RAG_RERANK_CANDIDATE_SIZE=10` 限制每次重排候选数，控制免费额度和延迟。正式评测时可以再调大。

检查服务：

```bash
curl -fsS http://localhost:8080/api/v1/actuator/health/readiness
```

## 4. 准备测试数据

创建知识库：

```bash
curl -sS -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H 'Content-Type: application/json' \
  -d '{"name":"Phase3 Reranker验证","description":"qwen3.7-text-rerank验证","ownerId":1}'
```

把响应中的 `data.id` 设置为：

```bash
KB_ID=替换为知识库ID
```

创建并上传测试文档：

```bash
mkdir -p /tmp/teammind-rerank-test
cat > /tmp/teammind-rerank-test/order-db.md <<'EOF'
# 订单服务数据库配置

订单服务使用 HikariCP 连接池。

最大连接数为 30。

连接超时时间为 3000 毫秒。

错误码 ORDER_POOL_EXHAUSTED 表示连接池耗尽。

连接池耗尽时，应先检查慢 SQL、长事务和连接泄漏。
EOF

curl -sS -X POST \
  "http://localhost:8080/api/v1/documents/upload?knowledgeBaseId=${KB_ID}&ownerId=1" \
  -F "file=@/tmp/teammind-rerank-test/order-db.md"
```

把文档 ID 设置为：

```bash
DOC_ID=替换为文档ID
```

轮询到完成：

```bash
for i in $(seq 1 120); do
  RESPONSE=$(curl -fsS "http://localhost:8080/api/v1/documents/${DOC_ID}/status?ownerId=1")
  echo "$RESPONSE"
  echo "$RESPONSE" | grep -q '"processingStatus":"COMPLETED"' && break
  echo "$RESPONSE" | grep -q '"processingStatus":"FAILED"' && break
  sleep 2
done
```

## 5. 验证 HYBRID 与 HYBRID_RERANK

先调用不使用重排的混合检索：

```bash
curl -sS -X POST http://localhost:8080/api/v1/retrieval/search \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"HikariCP 最大连接数是多少？\",\"knowledgeBaseIds\":[${KB_ID}],\"ownerId\":1,\"topK\":5,\"strategy\":\"HYBRID\"}"
```

再调用重排：

```bash
curl -sS -X POST http://localhost:8080/api/v1/retrieval/search \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"HikariCP 最大连接数是多少？\",\"knowledgeBaseIds\":[${KB_ID}],\"ownerId\":1,\"topK\":5,\"strategy\":\"HYBRID_RERANK\"}"
```

验收标准：

- 两次都返回真实 Chunk。
- `HYBRID` 结果包含 `denseRank`、`bm25Rank`、`rrfScore`，`rerankScore` 为 `null`。
- `HYBRID_RERANK` 结果包含非空 `rerankScore`。
- 第一条结果的原文包含 `HikariCP` 和 `30`。
- 响应中的 `strategy` 为 `HYBRID_RERANK`。

## 6. 验证问答与 Evidence

```bash
curl -sS -X POST http://localhost:8080/api/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d "{\"message\":\"订单服务的最大连接数是多少？\",\"knowledgeBaseIds\":[${KB_ID}],\"ownerId\":1,\"topK\":5,\"strategy\":\"HYBRID_RERANK\"}"
```

记录响应中的 `traceId`：

```bash
TRACE_ID=替换为traceId
```

查询 Evidence：

```bash
curl -sS "http://localhost:8080/api/v1/evidence/${TRACE_ID}?ownerId=1"
```

必须确认：

- 回答包含“30”。
- `citations` 指向 `order-db.md` 的真实 Chunk。
- 模型实际引用的证据 `cited=true`。
- Evidence 中包含非空 `rerankScore`。
- Evidence 的 `reranked=true`。
- 如果回答使用 `[1]`，编号对应的第一条证据与响应 `citations[0]` 的 `chunkId` 一致。

## 7. 额度与排错

重排消耗的是输入 Token，计算方式近似为 Query Token 乘以文档数，再加上所有文档 Token。先用 10 个候选和 `topK=5` 验证，不要直接压测。

如果应用返回 `50301`：

- 检查启动应用的终端中是否显式设置了 `RERANK_API_KEY`。
- 检查 `RERANK_BASE_URL` 是否以 `/api/v1` 结尾。
- 检查 `RERANK_MODEL` 是否为 `qwen3.7-text-rerank`。
- 先用第 2 节直连命令排除密钥、业务空间和模型权限问题。

如果直连成功但应用失败，检查应用启动日志中的上游错误，并确认启动的是重新打包后的 JAR。
