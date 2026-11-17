package com.teammind.knowledge.infrastructure.vector;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.VectorStoreGateway;
import com.teammind.knowledge.config.QdrantKnowledgeProperties;
import com.teammind.knowledge.domain.Chunk;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class QdrantVectorStoreGateway implements VectorStoreGateway {
    private final QdrantClient client;
    private final QdrantKnowledgeProperties properties;
    private final Duration timeout;
    private final Object collectionLock = new Object();

    public QdrantVectorStoreGateway(QdrantClient client, QdrantKnowledgeProperties properties) {
        this.client = client;
        this.properties = properties;
        this.timeout = Duration.ofSeconds(30);
    }

    @Override
    public void ensureCollection(int dimension) {
        if (dimension != properties.dimension()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Embedding 向量维度与配置不一致");
        }
        try {
            synchronized (collectionLock) {
                if (!client.collectionExistsAsync(properties.collection(), timeout).get()) {
                    client.createCollectionAsync(properties.collection(), Collections.VectorParams.newBuilder()
                            .setSize(dimension).setDistance(Collections.Distance.Cosine).build(), timeout).get();
                    return;
                }
                // 已存在的集合必须与当前 Embedding 配置维度一致，否则后续写入会得到难以排查的向量错误。
                long actualDimension = client.getCollectionInfoAsync(properties.collection(), timeout).get()
                        .getConfig().getParams().getVectorsConfig().getParams().getSize();
                if (actualDimension != dimension) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "向量集合维度与当前 Embedding 配置不一致，请更换集合名称或重新建库");
                }
            }
        } catch (BusinessException exception) {
            // 维度配置错误属于业务可定位问题，不能被统一转换成服务不可用。
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void upsert(List<Chunk> chunks, List<List<Float>> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "分块与向量数量不一致");
        }
        List<Points.PointStruct> points = java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> toPoint(chunks.get(index), vectors.get(index))).toList();
        execute(() -> client.upsertAsync(properties.collection(), points, timeout).get());
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        // 文档可能尚未完成过任何入库，此时集合不存在属于正常的幂等删除场景。
        if (!collectionExists()) {
            return;
        }
        Common.Filter filter = Common.Filter.newBuilder()
                .addMust(ConditionFactory.match("document_id", documentId)).build();
        execute(() -> client.deleteAsync(properties.collection(), filter, timeout).get());
    }

    @Override
    public List<SearchResult> search(List<Float> queryVector, List<Long> knowledgeBaseIds, int topK, double threshold) {
        // 首次使用知识库时集合可能尚未创建，返回空结果比抛出服务异常更符合业务语义。
        if (!collectionExists()) {
            return List.of();
        }
        try {
            Points.SearchPoints request = Points.SearchPoints.newBuilder()
                    .setCollectionName(properties.collection())
                    .addAllVector(queryVector)
                    .setLimit(topK)
                    .setScoreThreshold((float) threshold)
                    .setFilter(Common.Filter.newBuilder()
                            .addMust(ConditionFactory.matchValues("knowledge_base_id", knowledgeBaseIds)))
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true))
                    .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false))
                    .build();
            return client.searchAsync(request, timeout).get().stream()
                    .map(point -> new SearchResult(point.getId().getUuid(), point.getScore())).toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    private Points.PointStruct toPoint(Chunk chunk, List<Float> vector) {
        UUID vectorId = UUID.nameUUIDFromBytes(("document:" + chunk.getDocumentId() + ":version:"
                + chunk.getDocumentVersion() + ":chunk:" + chunk.getChunkIndex())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        chunk.setVectorId(vectorId.toString());
        Map<String, JsonWithInt.Value> payload = Map.of(
                "knowledge_base_id", ValueFactory.value(chunk.getKnowledgeBaseId()),
                "document_id", ValueFactory.value(chunk.getDocumentId()),
                "document_version", ValueFactory.value(chunk.getDocumentVersion()),
                "chunk_index", ValueFactory.value(chunk.getChunkIndex()));
        return Points.PointStruct.newBuilder().setId(PointIdFactory.id(vectorId))
                .setVectors(VectorsFactory.vectors(vector)).putAllPayload(payload).build();
    }

    private boolean collectionExists() {
        try {
            return Boolean.TRUE.equals(client.collectionExistsAsync(properties.collection(), timeout).get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    private void execute(IoOperation operation) {
        try {
            operation.run();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    private BusinessException unavailable(Exception exception) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "向量数据库操作失败", exception);
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws Exception;
    }
}
