# Phase 3 功能人工验收手册

本手册用于在真实启动的应用上逐项验证 Phase 3 的检索链路。它验证的是**功能和数据结构是否正确**，不代表模型效果好坏；效果评测属于 Phase 7。

前置条件：

- 应用已启动，`GET /api/v1/actuator/health/readiness` 中 `db`、`redis`、`qdrant` 均为 `UP`。
- 至少有一个知识库和一篇处理状态为 `COMPLETED` 的文档。
- 如果验证 `DENSE`、`HYBRID` 或 `HYBRID_RERANK`，需要 `QWEN_API_KEY`（或 Ollama 的 Embedding）；验证 `HYBRID_RERANK` 还需要可用的 Reranker 密钥，默认复用 `QWEN_API_KEY`。
- 仅验证 `BM25` 不需要模型密钥。

先设置变量：

```bash
BASE=http://localhost:8080
OWNER=1
KB=你的知识库ID
DOC=你的文档ID
```

下文使用 `jq` 格式化响应；没有安装时可以用 `python3 -m json.tool` 代替，或先执行 `brew install jq`。

确认文档已处理完成：

```bash
curl -sS "$BASE/api/v1/documents/$DOC/status?ownerId=$OWNER" | jq
```

期望 `data.status` 为 `COMPLETED`。如果状态是 `PENDING` 或 `PROCESSING`，等待完成后再继续；如果是 `FAILED`，先查看 `failureReason`。

## 0. 确认 BM25 词元已经生成

Phase 2 的旧文档如果没有重处理，`search_tokens` 为空，BM25 不会命中。执行：

```bash
docker exec -it team-mind-postgres psql -U teammind -d teammind -c "
SELECT document_id, count(*) AS chunks,
       count(*) FILTER (WHERE search_tokens IS NOT NULL AND search_tokens <> '') AS with_tokens
FROM knowledge_chunk
GROUP BY document_id;"
```

`with_tokens` 应与 `chunks` 一致。如果为 0，调用重处理接口并等待 `COMPLETED`：

```bash
curl -X POST "$BASE/api/v1/documents/$DOC/reprocess?ownerId=$OWNER"
```

## 1. 四种策略一次跑完

先选一个文档中确实出现的关键词作为查询。下面的 `HikariCP 最大连接数` 只是示例，**不要直接照抄**，除非你的文档确实包含这些词；否则 BM25 返回空是正常现象。可以先用第 0 节的 SQL 看一段真实 `content`，从里面挑一个词。依次调用：

```bash
for s in DENSE BM25 HYBRID HYBRID_RERANK; do
  echo "===== $s ====="
  curl -sS -X POST "$BASE/api/v1/retrieval/search" \
    -H 'Content-Type: application/json' \
    -d "{\"query\":\"HikariCP 最大连接数\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5,\"strategy\":\"$s\"}" \
  | jq '{code, strategy:.data.strategy, traceId:.data.traceId,
         chunks:[.data.chunks[]? | {chunkId,documentName,documentVersion,
                 denseRank,bm25Rank,denseScore,bm25Score,rrfScore,rerankScore}]}'
done
```

预期字段组合如下（JSON 中值为 `null` 的字段会被全局省略，所以“没有该字段”就是“该阶段未执行”）：

| 策略 | denseRank/denseScore | bm25Rank/bm25Score | rrfScore | rerankScore |
| --- | --- | --- | --- | --- |
| `DENSE` | 有 | 无 | 无 | 无 |
| `BM25` | 无 | 有 | 无 | 无 |
| `HYBRID` | 至少一路有 | 至少一路有 | 有 | 无 |
| `HYBRID_RERANK` | 有 | 有 | 有 | 有 |

`HYBRID` 中某个分块可能只被 Dense 或只被 BM25 召回，因此只有一路的排名；两路都召回时两路排名都有。

## 2. 验证 BM25 关键词召回

BM25 是纯关键词召回，不调用 Embedding。用一个文档里明确出现的英文标识符或两字以上中文词：

```bash
curl -sS -X POST "$BASE/api/v1/retrieval/search" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"HikariCP\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5,\"strategy\":\"BM25\"}" \
| jq '{strategy:.data.strategy, chunks:[.data.chunks[]? | {documentName,documentVersion,pageNumber,bm25Rank,bm25Score,content}]}'
```

判断标准：

1. `chunks` 不为空。
2. 每个结果都有 `bm25Rank` 和 `bm25Score`，且**没有** `denseRank`、`denseScore`、`rrfScore`、`rerankScore`。
3. 用一个文档中绝不存在的单词，例如 `ZXQABSENT9137`，再查一次：

```bash
curl -sS -X POST "$BASE/api/v1/retrieval/search" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"ZXQABSENT9137\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5,\"strategy\":\"BM25\"}" \
| jq '{strategy:.data.strategy, chunks:.data.chunks}'
```

期望 `chunks` 为 `[]`。如果 Dense 对这个不存在的词仍返回结果，是正常的语义近邻行为，正好说明 BM25 不是靠向量兜底。

注意：分词器会把英文统一小写，并按非字母数字边界切分；`spring.datasource.hikari` 会得到 `spring`、`datasource`、`hikari` 等词元。中文按二元词切分，单个汉字通常无法命中只保存二元词的内容。

## 3. 验证 Dense 向量召回

换一个语义相近、但文档里不一定逐字出现的说法，例如文档写“最大连接数”，查询“数据库连接池上限”。执行：

```bash
curl -sS -X POST "$BASE/api/v1/retrieval/search" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"数据库连接池上限\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5,\"strategy\":\"DENSE\"}" \
| jq '{strategy:.data.strategy, chunks:[.data.chunks[]? | {documentName,documentVersion,denseRank,denseScore,content}]}'
```

判断标准：

1. `chunks` 不为空，且每个结果都有 `denseRank`、`denseScore`。
2. 没有 `bm25Rank`、`bm25Score`、`rrfScore`、`rerankScore`。
3. 如果 `chunks` 为空，先检查 `data.code` 是否为 `50301`（缺少 `QWEN_API_KEY` 或 Embedding 服务不可用），再尝试更贴近文档原文的查询，或临时降低 `RAG_SCORE_THRESHOLD`。

字段存在只能证明 Dense 链路执行了；语义效果需要固定问题集对比，不能由单次查询断言。

## 4. 验证 Dense + BM25 并行检索与 RRF 融合

先保存一次 HYBRID 的完整响应：

```bash
curl -sS -X POST "$BASE/api/v1/retrieval/search" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"HikariCP 最大连接数\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5,\"strategy\":\"HYBRID\"}" \
  > /tmp/teammind-hybrid.json
```

检查 RRF 分数是否等于两路倒数排名之和。默认 `RAG_RRF_K=60`，如果改过该变量，把脚本里的 `k` 改成实际值：

```bash
python3 - <<'PY'
import json

data = json.load(open('/tmp/teammind-hybrid.json'))['data']
if not data.get('chunks'):
    print('RRF_CHECK FAIL (no chunks)')
    raise SystemExit(1)
k = 60
previous = None
passed = True
for chunk in data['chunks']:
    expected = 0.0
    if chunk.get('denseRank'):
        expected += 1.0 / (k + chunk['denseRank'])
    if chunk.get('bm25Rank'):
        expected += 1.0 / (k + chunk['bm25Rank'])
    actual = chunk.get('rrfScore', 0.0)
    same = abs(actual - expected) < 1e-6
    ordered = previous is None or actual <= previous + 1e-12
    passed = passed and same and ordered
    previous = actual
    print(f"{chunk['chunkId']} rrf={actual:.8f} expected={expected:.8f} "
          f"{'OK' if same else 'MISMATCH'}{'' if ordered else ' ORDER_FAIL'}")
print('RRF_CHECK', 'PASS' if passed else 'FAIL')
PY
```

判断标准：

1. 结果按 `rrfScore` 降序排列。
2. 每个分块的 `rrfScore` 与 `1/(k+denseRank) + 1/(k+bm25Rank)` 一致；只有一路召回时只计算那一路。
3. 结果中同时能看到 Dense 和 BM25 的贡献，说明两路都执行了，而不是一路失败后退化成另一路。

HYBRID 是“两路都成功才返回”的策略：任何一路超时或报错都会返回 `50301`，不会静默降级。

## 5. 验证 Reranker

用同一个查询调用 `HYBRID_RERANK`：

```bash
curl -sS -X POST "$BASE/api/v1/retrieval/search" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"HikariCP 最大连接数\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5,\"strategy\":\"HYBRID_RERANK\"}" \
  > /tmp/teammind-rerank.json

jq '{strategy:.data.strategy, traceId:.data.traceId,
     chunks:[.data.chunks[] | {chunkId,documentVersion,denseRank,bm25Rank,rrfScore,rerankScore,content}]}' \
  /tmp/teammind-rerank.json
```

判断标准：

1. 每个结果都有 `rerankScore`，并且同时保留 `denseRank`、`bm25Rank`、`rrfScore`。
2. 结果按 `rerankScore` 降序排列。
3. 与第 4 步的 `HYBRID` 结果对比，顺序可能变化；顺序不变也不代表失败，只要 `rerankScore` 和 Evidence 的 `reranked=true` 存在，就说明模型确实执行了。
4. 缺少重排密钥时返回 `50301`，错误信息为“未配置 RERANK_API_KEY 或 QWEN_API_KEY，无法重排序”。

Ollama profile 只覆盖 Chat 和 Embedding，默认不提供 Reranker；需要额外配置兼容的 Rerank 服务，或使用 DashScope 密钥。

## 6. 验证文档版本过滤

准备两个同名但内容不同的文件，分别带有全局唯一的英文词元。用时间戳避免和已有文档重名：

```bash
NAME="phase3-version-$(date +%s).txt"
mkdir -p /tmp/teammind-v1 /tmp/teammind-v2
printf 'ALPHAUNIQUE9137 订单模块最大连接数 100\n' > "/tmp/teammind-v1/$NAME"
printf 'BETAUNIQUE8246 订单模块最大连接数 200\n' > "/tmp/teammind-v2/$NAME"
```

上传第一版并等待完成：

```bash
V1=$(curl -sS -X POST "$BASE/api/v1/documents/upload?knowledgeBaseId=$KB&ownerId=$OWNER" \
  -F "file=@/tmp/teammind-v1/$NAME")
echo "$V1" | jq '{id:.data.id, version:.data.currentVersion, status:.data.processingStatus}'
DOC2=$(echo "$V1" | jq -r '.data.id')

until [ "$(curl -sS "$BASE/api/v1/documents/$DOC2/status?ownerId=$OWNER" | jq -r '.data.status')" = "COMPLETED" ]; do
  sleep 2
done
```

上传第二版（同名文件），等待完成：

```bash
V2=$(curl -sS -X POST "$BASE/api/v1/documents/upload?knowledgeBaseId=$KB&ownerId=$OWNER" \
  -F "file=@/tmp/teammind-v2/$NAME")
echo "$V2" | jq '{id:.data.id, version:.data.currentVersion, status:.data.processingStatus}'
DOC2=$(echo "$V2" | jq -r '.data.id')

until [ "$(curl -sS "$BASE/api/v1/documents/$DOC2/status?ownerId=$OWNER" | jq -r '.data.status')" = "COMPLETED" ]; do
  sleep 2
done
```

此时 `DOC2` 不变，`currentVersion` 应为 `2`。分别用两个唯一词做 BM25 查询：

```bash
for q in ALPHAUNIQUE9137 BETAUNIQUE8246; do
  echo "===== $q ====="
  curl -sS -X POST "$BASE/api/v1/retrieval/search" \
    -H 'Content-Type: application/json' \
    -d "{\"query\":\"$q\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5,\"strategy\":\"BM25\"}" \
  | jq '{chunks:[.data.chunks[]? | {documentName,documentVersion,content}]}'
done
```

预期：

- `ALPHAUNIQUE9137`（只存在于 v1）返回 `chunks: []`，旧版本不能参与召回。
- `BETAUNIQUE8246`（只存在于 v2）返回 v2 的分块，`documentVersion` 为 `2`。
- 四种策略都应该只返回 `documentVersion=2`；Dense 即使召回近邻，也会被 `EffectiveChunkLoader` 的当前版本校验过滤。

## 7. 验证 Evidence Board

每次检索和问答都会返回 32 位十六进制 `traceId`。以第 5 步的响应为例：

```bash
TRACE=$(jq -r '.data.traceId' /tmp/teammind-rerank.json)

curl -sS "$BASE/api/v1/evidence/$TRACE?ownerId=$OWNER" \
| jq '.data[] | {chunkId,documentName,documentVersion,retrievalType,
                  denseRank,bm25Rank,rrfScore,rerankScore,reranked,cited,content}'
```

预期：

| 请求策略 | Evidence `retrievalType` | 其他字段 |
| --- | --- | --- |
| `DENSE` | `DENSE` | dense 排名/分数，`reranked=false` |
| `BM25` | `BM25` | bm25 排名/分数，`reranked=false` |
| `HYBRID` | `HYBRID` | 两路排名和 `rrfScore`，`reranked=false` |
| `HYBRID_RERANK` | `HYBRID` | `rerankScore` 有值，`reranked=true` |

注意：`HYBRID_RERANK` 在 Evidence 里的 `retrievalType` 仍然是 `HYBRID`，因为重排是混合检索后的一个阶段，不是独立的召回来源；用 `reranked=true` 和 `rerankScore` 判断是否重排。独立检索的 `cited` 都是 `false`。

问答的引用关联：

```bash
curl -sS -X POST "$BASE/api/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"message\":\"HikariCP 最大连接数是多少？\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5}" \
  > /tmp/teammind-chat.json

jq '{answer:.data.answer, strategy:.data.strategy, traceId:.data.traceId,
     citations:[.data.citations[]? | {index,chunkId,documentVersion,score,rerankScore}]}' \
  /tmp/teammind-chat.json
```

`citations` 会列出本次 Top K 的全部证据，不是只列出被引用的证据。只有回答正文中出现 `[编号]` 的那些 Chunk，Evidence 才会是 `cited=true`。可以自动核对：

```bash
TRACE=$(jq -r '.data.traceId' /tmp/teammind-chat.json)

BASE="$BASE" OWNER="$OWNER" TRACE="$TRACE" python3 - <<'PY'
import json, os, re, urllib.request

base = os.environ['BASE']
owner = os.environ['OWNER']
trace = os.environ['TRACE']
chat = json.load(open('/tmp/teammind-chat.json'))['data']
evidence = json.load(urllib.request.urlopen(
    f"{base}/api/v1/evidence/{trace}?ownerId={owner}"))['data']
cited_indexes = {int(i) for i in re.findall(r'\[(\d+)]', chat['answer'])}
by_chunk = {e['chunkId']: e['cited'] for e in evidence}
print('cited indexes:', sorted(cited_indexes))
for citation in chat['citations']:
    expected = citation['index'] in cited_indexes
    actual = by_chunk.get(citation['chunkId'])
    print(citation['index'], citation['chunkId'], 'expected=', expected,
          'actual=', actual, 'OK' if expected == actual else 'MISMATCH')
PY
```

如果模型没有在回答里写 `[编号]`，所有 `cited` 都会是 `false`，这是正常的；可以换一个更明确要求引用的问法，或检查 RAG prompt。

数据库侧也可以直接查：

```sql
SELECT trace_id, status, strategy, query, total_duration, answer
FROM ai_trace
ORDER BY created_at DESC
LIMIT 5;

SELECT trace_id, retrieval_type, document_name, document_version,
       dense_rank, bm25_rank, rrf_score, rerank_score, reranked, cited
FROM ai_evidence
WHERE trace_id = '替换为traceId'
ORDER BY id;
```

## 8. 自动化集成测试

如果只想验证链路结构，不调用真实模型，可以运行项目自带的集成测试：

```bash
./scripts/verify-infrastructure.sh
```

它会用独立端口的 PostgreSQL、Redis、Qdrant 跑一遍“PDF 上传 → 分块 → 向量入库 → BM25/RRF/重排 → 带引用回答 → Evidence → 删除清理”，外部模型替换为本地确定性实现。它验证的是机制和 SQL，不验证真实 Qwen/Reranker 的回答质量。

## 9. 验收结论怎么写

功能通过的判断标准是：

- 四种策略返回的字段组合符合第 1 节表格。
- BM25 对不存在词返回空，对存在词返回 `bm25Rank`。
- HYBRID 的 `rrfScore` 与公式一致且降序。
- HYBRID_RERANK 有 `rerankScore`，Evidence 有 `reranked=true`。
- 同名新版本上传后，所有策略都不返回旧版本。
- 问答的 `traceId` 能查到 Evidence，回答中的 `[编号]` 与 `cited=true` 对得上。

模型是否真的比单路检索更准，需要固定问题集、人工标注和 Phase 7 的评测体系，不能由上述功能验收代替。

## 10. 返回空结果怎么排查

先看完整响应，不要只看 `chunks`，因为 `jq` 的 `[]?` 会把错误响应的 `data: null` 也显示成空数组：

```bash
curl -sS -X POST "$BASE/api/v1/retrieval/search" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"你的查询\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5,\"strategy\":\"HYBRID_RERANK\"}" | jq .
```

### 10.1 `code` 不是 `0`

- `50301` 且 `message` 包含“未配置 RERANK_API_KEY 或 QWEN_API_KEY，无法重排序”：当前是 Ollama 模式或没有配置重排密钥。Ollama 只提供 Chat 和 Embedding，不提供 Reranker；需要设置 `RERANK_API_KEY`、`RERANK_BASE_URL`、`RERANK_MODEL`，然后重启。
- `50301` 且 `message` 包含“重排序模型服务调用失败/返回格式异常/连接或响应超时”：检查重排地址是否只配置到 `/api/v1`、模型是否已开通、API Key 是否属于对应的业务空间。可以按 `docs/phase3-qwen37-rerank-verification.md` 第 2 节直接 curl 模型验证。
- `50301` 且与 Embedding 有关：检查 `QWEN_API_KEY`，或确认 Ollama 的 `qwen3-embedding:0.6b` 已拉取并正在运行。

### 10.2 `code` 是 `0`，但某个策略的 `chunks` 为空

**BM25 为空**：

```bash
docker exec -it team-mind-postgres psql -U teammind -d teammind -c "
SELECT count(*) AS chunks,
       count(*) FILTER (WHERE search_tokens IS NOT NULL AND search_tokens <> '') AS with_tokens
FROM knowledge_chunk WHERE document_id = $DOC;"
```

- `with_tokens = 0`：旧文档没有 BM25 词元，调用 `POST /api/v1/documents/$DOC/reprocess?ownerId=1`，等 `COMPLETED` 后再查。
- `with_tokens > 0`：说明词元已生成，只是查询词和文档内容不匹配。查看真实内容：

```bash
docker exec -it team-mind-postgres psql -U teammind -d teammind -c "
SELECT chunk_index, left(content, 200) AS content,
       left(search_tokens, 200) AS tokens
FROM knowledge_chunk WHERE document_id = $DOC
ORDER BY chunk_index LIMIT 5;"
```

从 `content` 里挑一个真实出现的词再查。英文大小写不影响（会统一小写），但 `HikariCP` 和 `HikariDataSource`、`Hikari CP` 是不同词元；中文单个汉字通常不会命中二元词。

**DENSE 为空**：

- 先确认 `code` 不是 `50301`。
- 尝试更贴近文档原文的查询，或检查 `RAG_SCORE_THRESHOLD`（默认 `0.25`）是否过高。
- 确认 Qdrant 集合与当前 Embedding 模型一致：云端和 Ollama 使用不同集合，不能复用旧向量。

**HYBRID 为空**：

HYBRID 需要 Dense 和 BM25 两路都成功。如果其中一路报错，整个请求会返回 `50301`，不会降级。先分别验证 `DENSE` 和 `BM25`，两者都正常后再看 HYBRID。

**HYBRID_RERANK 有 `code=0` 但为空**：

- 先看 `HYBRID` 是否有结果；如果 HYBRID 本身为空，重排也不会有候选。
- 如果 HYBRID 有结果但 `HYBRID_RERANK` 为空，说明 Reranker 返回了空列表或被服务层全部过滤；查看应用日志中的重排异常，并按第 2 节直接 curl 重排模型。

### 10.3 问答看起来正常，但检索为空

检查问答响应是否真的带回了引用：

```bash
curl -sS -X POST "$BASE/api/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"message\":\"你的问题\",\"knowledgeBaseIds\":[$KB],\"ownerId\":$OWNER,\"topK\":5}" \
| jq '{answer:.data.answer, strategy:.data.strategy, citationCount:(.data.citations|length), traceId:.data.traceId}'
```

如果 `citationCount` 为 `0`，回答很可能是“当前知识库没有找到足够依据”的兜底文本，并不代表检索成功。
