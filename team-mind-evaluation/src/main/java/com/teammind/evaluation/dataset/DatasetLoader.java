package com.teammind.evaluation.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 从 classpath 加载内置评测数据集。数据集名称使用白名单，避免任意资源读取。
 * 每次加载都计算 SHA-256，运行记录可以通过 hash 追溯同一版本的数据集。
 */
@Component
public class DatasetLoader {
    public static final String TASK_DATASET = "task-classification";
    public static final String RAG_DATASET = "rag-retrieval";
    public static final String MEMORY_DATASET = "memory-retrieval";
    private static final Set<String> ALLOWED = Set.of(TASK_DATASET, RAG_DATASET, MEMORY_DATASET);
    private static final int MAX_CASES = 200;

    private final ObjectMapper objectMapper;

    public DatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DatasetResource<TaskDataset> loadTask(String name) {
        return load(name == null || name.isBlank() ? TASK_DATASET : name,
                TaskDataset.class, "TASK");
    }

    public DatasetResource<RagDataset> loadRag(String name) {
        return load(name == null || name.isBlank() ? RAG_DATASET : name,
                RagDataset.class, "RAG");
    }

    public DatasetResource<MemoryDataset> loadMemory(String name) {
        return load(name == null || name.isBlank() ? MEMORY_DATASET : name,
                MemoryDataset.class, "MEMORY");
    }

    public List<DatasetDescriptor> list() {
        return List.of(
                descriptor(loadTask(null), "TASK"),
                descriptor(loadRag(null), "RAG"),
                descriptor(loadMemory(null), "MEMORY"));
    }

    private <T extends EvaluationDataset> DatasetResource<T> load(String name, Class<T> type, String typeName) {
        if (!ALLOWED.contains(name)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评测数据集不存在");
        }
        ClassPathResource resource = new ClassPathResource("evaluation/" + name + ".json");
        if (!resource.exists()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评测数据集文件不存在");
        }
        try (InputStream inputStream = resource.getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            T dataset = objectMapper.readValue(bytes, type);
            if (dataset.caseCount() == 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "评测数据集不能为空");
            }
            if (dataset.caseCount() > MAX_CASES) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "评测数据集最多包含 " + MAX_CASES + " 个用例");
            }
            return new DatasetResource<>(name, sha256(bytes), dataset, typeName);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "评测数据集读取失败", exception);
        }
    }

    private DatasetDescriptor descriptor(DatasetResource<? extends EvaluationDataset> resource, String type) {
        return new DatasetDescriptor(type, resource.name(), resource.dataset().version(),
                resource.dataset().caseCount(), resource.hash());
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record DatasetResource<T extends EvaluationDataset>(String name, String hash, T dataset,
            String type) {
    }

    public record DatasetDescriptor(String type, String name, String version, int caseCount, String hash) {
    }
}
