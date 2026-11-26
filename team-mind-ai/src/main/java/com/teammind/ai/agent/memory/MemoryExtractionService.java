package com.teammind.ai.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.ai.model.ChatCompletionGateway;
import com.teammind.ai.prompt.MemoryPrompts;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.memory.application.ConversationService;
import com.teammind.memory.application.MemoryService;
import com.teammind.memory.config.MemoryProperties;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryType;
import com.teammind.memory.domain.StoredMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从会话中提取长期记忆候选。提取结果只保存为 DRAFT，
 * 必须由用户确认后才变为 ACTIVE 并参与后续检索。
 */
@Service
public class MemoryExtractionService {
    private final ConversationService conversationService;
    private final MemoryService memoryService;
    private final ChatCompletionGateway chatGateway;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper;

    public MemoryExtractionService(ConversationService conversationService, MemoryService memoryService,
            ChatCompletionGateway chatGateway, MemoryProperties properties, ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.memoryService = memoryService;
        this.chatGateway = chatGateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public MemoryExtractionResult extract(String conversationId, Long ownerId) {
        conversationService.get(conversationId, ownerId);
        List<StoredMessage> messages = conversationService.loadMessages(conversationId, ownerId, 60);
        if (messages.isEmpty()) {
            return new MemoryExtractionResult(List.of(), List.of("会话没有可提取的消息"));
        }
        String raw = chatGateway.generate(MemoryPrompts.EXTRACTION_SYSTEM_PROMPT,
                MemoryPrompts.buildExtractionContent(messages));
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "记忆提取模型未返回有效内容");
        }
        JsonNode root = parseJson(raw);
        JsonNode array = root.isArray() ? root : root.path("memories");
        if (!array.isArray()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "记忆提取结果格式不正确");
        }
        String sourceTraceId = messages.stream()
                .map(StoredMessage::traceId)
                .filter(traceId -> traceId != null && !traceId.isBlank())
                .reduce((first, second) -> second).orElse(null);
        List<Memory> memories = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int accepted = 0;
        for (JsonNode candidate : array) {
            if (accepted >= properties.maxExtractionCandidates()) {
                break;
            }
            try {
                MemoryType type = parseType(candidate.path("type").asText(""));
                if (type == null) {
                    warnings.add("已跳过未知记忆类型");
                    continue;
                }
                String content = candidate.path("content").asText("").trim();
                if (content.isEmpty()) {
                    warnings.add("已跳过空记忆内容");
                    continue;
                }
                double confidence = candidate.path("confidence").asDouble(0);
                if (!Double.isFinite(confidence) || confidence < 0.5 || confidence > 1) {
                    warnings.add("已跳过置信度不足的记忆候选");
                    continue;
                }
                String title = candidate.path("title").asText("");
                String summary = candidate.path("summary").asText("");
                memories.add(memoryService.createDraft(ownerId, type, title, content, summary,
                        conversationId, sourceTraceId, confidence));
                accepted++;
            } catch (RuntimeException exception) {
                // 单个候选非法不影响其他候选入库。
                warnings.add("已跳过一条不合法记忆候选");
            }
        }
        return new MemoryExtractionResult(memories, warnings);
    }

    private JsonNode parseJson(String raw) {
        String content = stripCodeFence(raw);
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "记忆提取结果为空");
            }
            return root;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "记忆提取结果不是合法 JSON", exception);
        }
    }

    private MemoryType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MemoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return trimmed;
        }
        String withoutStart = trimmed.substring(firstLineEnd + 1);
        int fenceEnd = withoutStart.lastIndexOf("```");
        return fenceEnd < 0 ? withoutStart.trim() : withoutStart.substring(0, fenceEnd).trim();
    }
}
