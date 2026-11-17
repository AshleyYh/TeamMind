# Ollama 本地模型部署说明

## 推荐组合

48GB Apple Silicon Mac 推荐同时部署：

| 用途 | Ollama 模型 | 权重规模 | 输出维度 | 说明 |
|---|---|---:|---:|---|
| RAG 回答 | `qwen3.5:9b` | 约 6.6GB | - | 中文能力好，响应速度和内存占用平衡 |
| 文档向量 | `qwen3-embedding:0.6b` | 约 639MB | 1024 | 支持中文和长文本，维度与项目配置一致 |

两个模型的权重合计约 7 至 8GB。加上 Docker、Qdrant、上下文缓存和 macOS，正常仍有充足余量，不需要只部署文本模型。若希望更高回答质量，可改用 `qwen3.5:35b-a3b`，但它约 20GB，在 48GB 机器上同时保留两个模型和较长上下文后余量较小。

## 1. 拉取模型

确认 Ollama 已启动后执行：

```bash
ollama --version
ollama pull qwen3-embedding:0.6b
ollama pull qwen3.5:9b
ollama list
```

首次拉取需要下载数 GB 数据。Embedding 模型不能像聊天模型一样直接进入交互式对话，如果执行 `ollama run qwen3-embedding:0.6b` 后要求输入文本，这是预期行为。

## 2. 验证 OpenAI 兼容接口

项目通过 Ollama 的 OpenAI 兼容接口访问模型。先验证 Embedding，重点确认返回维度为 1024：

```bash
curl -s http://localhost:11434/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3-embedding:0.6b","input":["订单模块最大连接数"],"dimensions":1024}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("向量维度:", len(d["data"][0]["embedding"]))'
```

预期输出：

```text
向量维度: 1024
```

再验证文本模型：

```bash
curl -s http://localhost:11434/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3.5:9b","stream":false,"messages":[{"role":"user","content":"请用一句中文回答：什么是向量检索？"}]}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["choices"][0]["message"]["content"])'
```

如果这两个请求失败，不要先启动 TeamMind；应先检查 Ollama 是否监听 `11434`、模型名是否与 `ollama list` 完全一致，以及本机防火墙或代理是否拦截 `localhost`。

## 3. 启动项目

Ollama 在宿主机运行，PostgreSQL、Redis 和 Qdrant 继续使用 Docker Compose：

```bash
cd /Users/yuhang/Projects/TeamMind
docker compose up -d --wait

./mvnw -pl team-mind-web -am package -DskipTests
java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local,ollama
```

`ollama` profile 已设置以下默认值，不需要设置 `QWEN_API_KEY`：

```text
base-url:          http://localhost:11434/v1
embedding-model:   qwen3-embedding:0.6b
embedding-dimension: 1024
chat-model:        qwen3.5:9b
collection:        team_mind_chunks_local_qwen3_1024
```


`local` 负责本地开发用户和健康详情，`ollama` 负责本地模型配置，两者必须同时激活。Ollama 地址可以通过 `OLLAMA_BASE_URL` 覆盖；模型和集合也可以分别通过 `OLLAMA_CHAT_MODEL`、`OLLAMA_EMBEDDING_MODEL`、`OLLAMA_QDRANT_COLLECTION` 覆盖。

## 4. 重新建立向量索引

不同 Embedding 模型的向量维度即使相同，向量空间也不兼容。如果数据库中已有云端 Qwen 生成的向量，必须使用新的 Qdrant 集合重新生成，绝不能在旧集合中继续写入后再检索。

本地 profile 默认使用 `team_mind_chunks_local_qwen3_1024`。已有文档不需要重新上传原文件，可以对每个文档调用重处理接口：

```bash
curl -X POST "http://localhost:8080/api/v1/documents/文档ID/reprocess?ownerId=1"
```

随后查询状态，直到变为 `COMPLETED`。重处理会清理该文档旧分块，并使用本地 Embedding 写入新集合。云端旧集合可以暂时保留用于回退，确认本地结果稳定后再按需删除。

## 5. 内存与运行检查

处理文档或问答期间执行：

```bash
ollama ps
```

重点观察模型是否主要由 GPU 加载。如果百分比明显低于 100%，速度会显著下降。活动监视器中的 Ollama 内存应大致对应模型权重加上下文缓存，而不是模型下载体积的简单相加。

默认情况下，Ollama 会在模型闲置后卸载。问答时先做 Query Embedding、再调用文本模型，可能发生一次模型切换和重新加载，第一次回答会比后续稍慢。项目为本地模型设置了 180 秒读取超时以容纳首次加载。

若希望减少模型切换，可只保留当前组合运行，关闭其他大型本地模型；不建议为了“常驻两个模型”盲目设置超长 keep-alive，因为长上下文会额外占用内存。回答速度不足时可换 `qwen3.5:4b`；内存不足时优先降低 Ollama 上下文长度，而不是同时加载更大的文本模型。

## 6. 验收标准

启动后检查健康接口：

```bash
curl http://localhost:8080/api/v1/actuator/health
```

`db`、`redis`、`qdrant` 必须为 `UP`。上传一份 PDF，状态达到 `COMPLETED` 后提问，回答应返回至少一个 `citations`，引用中的 Chunk ID、文档名、版本和页码来自真实检索结果。

本地模型不需要执行消费真实 Qwen 配额的云端验收；若调用失败，先根据后端 `50301` 错误判断是 Ollama 未启动、模型名错误、连接超时还是向量维度不一致。

## 7. 切回云端

不激活 `ollama` profile，并按原方式设置云端配置即可：

```bash
export QWEN_API_KEY="你的 DashScope API Key"
./mvnw -pl team-mind-web -am package -DskipTests
java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local
```

云端和本地应使用不同的 Qdrant collection。切换模型供应商或 Embedding 模型时，都必须重新建立向量索引。
