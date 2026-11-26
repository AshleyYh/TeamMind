package com.teammind.memory.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.memory.config.MemoryProperties;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryStatus;
import com.teammind.memory.domain.MemoryType;
import com.teammind.memory.mapper.MemoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 长期记忆服务。
 *
 * <p>新记忆先保存为 DRAFT，只有用户确认后才变为 ACTIVE 并参与 Agent 检索。
 * 内容更新递增 version，失效和删除都保留来源会话/Trace，便于追溯。</p>
 */
@Service
public class MemoryService {
    private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_CONTENT_CHARS = 4000;
    private static final int MAX_TITLE_CHARS = 255;
    private static final int MAX_SUMMARY_CHARS = 2000;
    private static final int MAX_TOKENS = 8;

    private final MemoryMapper memoryMapper;
    private final ConversationService conversationService;
    private final MemoryProperties properties;

    public MemoryService(MemoryMapper memoryMapper, ConversationService conversationService,
            MemoryProperties properties) {
        this.memoryMapper = memoryMapper;
        this.conversationService = conversationService;
        this.properties = properties;
    }

    @Transactional
    public Memory createDraft(Long ownerId, MemoryType type, String title, String content, String summary,
            String sourceConversationId, String sourceTraceId, Double confidence) {
        requireOwner(ownerId);
        MemoryType safeType = type == null ? MemoryType.OTHER : type;
        String normalizedContent = requireContent(content);
        String normalizedSummary = normalizeSummary(summary, normalizedContent);
        String normalizedTitle = normalizeTitle(title, normalizedContent);
        String normalizedConversationId = normalizeSourceConversation(sourceConversationId, ownerId);
        String normalizedTraceId = normalizeTraceId(sourceTraceId);
        double safeConfidence = confidence == null ? 0.5 : normalizeConfidence(confidence);

        Memory existing = findDuplicate(ownerId, safeType, normalizedContent);
        if (existing != null) {
            // 重复提取应保持幂等，不产生多条相同记忆。
            return existing;
        }
        Memory memory = new Memory();
        memory.setUserId(ownerId);
        memory.setMemoryType(safeType);
        memory.setTitle(normalizedTitle);
        memory.setContent(normalizedContent);
        memory.setSummary(normalizedSummary);
        memory.setSourceConversationId(normalizedConversationId);
        memory.setSourceTraceId(normalizedTraceId);
        memory.setVersion(1);
        memory.setStatus(MemoryStatus.DRAFT);
        memory.setConfidence(safeConfidence);
        memory.setDeleted(false);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.insert(memory);
        return memory;
    }

    public Memory get(Long id, Long ownerId) {
        Memory memory = memoryMapper.selectById(id);
        if (memory == null || !memory.getUserId().equals(ownerId)) {
            // 不暴露其他用户的记忆是否存在。
            throw new BusinessException(ErrorCode.NOT_FOUND, "记忆不存在或不可访问");
        }
        return memory;
    }

    public List<Memory> list(Long ownerId, MemoryStatus status, MemoryType type, int limit) {
        requireOwner(ownerId);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        LambdaQueryWrapper<Memory> query = new LambdaQueryWrapper<Memory>()
                .eq(Memory::getUserId, ownerId);
        if (status != null) {
            query.eq(Memory::getStatus, status);
        }
        if (type != null) {
            query.eq(Memory::getMemoryType, type);
        }
        query.orderByDesc(Memory::getUpdatedAt).last("LIMIT " + safeLimit);
        return memoryMapper.selectList(query);
    }

    @Transactional
    public Memory confirm(Long id, Long ownerId) {
        Memory memory = get(id, ownerId);
        if (memory.getStatus() == MemoryStatus.INVALID) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "已失效的记忆不能重新确认");
        }
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(memory);
        return memory;
    }

    @Transactional
    public Memory invalidate(Long id, Long ownerId) {
        Memory memory = get(id, ownerId);
        memory.setStatus(MemoryStatus.INVALID);
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(memory);
        return memory;
    }

    @Transactional
    public Memory update(Long id, Long ownerId, MemoryType type, String title, String content,
            String summary) {
        Memory memory = get(id, ownerId);
        if (memory.getStatus() == MemoryStatus.INVALID) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "已失效的记忆不能修改");
        }
        String normalizedContent = requireContent(content);
        memory.setMemoryType(type == null ? memory.getMemoryType() : type);
        memory.setTitle(normalizeTitle(title, normalizedContent));
        memory.setContent(normalizedContent);
        memory.setSummary(normalizeSummary(summary, normalizedContent));
        memory.setVersion(memory.getVersion() + 1);
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(memory);
        return memory;
    }

    @Transactional
    public void delete(Long id, Long ownerId) {
        Memory memory = get(id, ownerId);
        memory.setStatus(MemoryStatus.INVALID);
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(memory);
        // 逻辑删除后不再出现在列表和检索中，但来源数据仍保留用于历史追溯。
        memoryMapper.deleteById(id);
    }

    /** 只返回 ACTIVE 记忆；查询词按空白和标点拆分，任一关键词命中即可。 */
    public List<Memory> search(Long ownerId, String query, int limit) {
        requireOwner(ownerId);
        int safeLimit = Math.min(Math.max(limit, 1), properties.maxSearchResults());
        LambdaQueryWrapper<Memory> wrapper = new LambdaQueryWrapper<Memory>()
                .eq(Memory::getUserId, ownerId)
                .eq(Memory::getStatus, MemoryStatus.ACTIVE);
        List<String> tokens = tokenize(query);
        if (!tokens.isEmpty()) {
            wrapper.and(outer -> {
                for (int index = 0; index < tokens.size(); index++) {
                    if (index > 0) {
                        outer.or();
                    }
                    String token = tokens.get(index);
                    outer.and(inner -> inner.like(Memory::getContent, token)
                            .or().like(Memory::getTitle, token)
                            .or().like(Memory::getSummary, token));
                }
            });
        }
        wrapper.orderByDesc(Memory::getUpdatedAt).last("LIMIT " + safeLimit);
        List<Memory> memories = memoryMapper.selectList(wrapper);
        markUsed(memories);
        return memories;
    }

    private void markUsed(List<Memory> memories) {
        if (memories.isEmpty()) {
            return;
        }
        List<Long> ids = memories.stream().map(Memory::getId).toList();
        memoryMapper.update(null, new LambdaUpdateWrapper<Memory>()
                .in(Memory::getId, ids)
                .set(Memory::getLastUsedAt, LocalDateTime.now()));
    }

    private Memory findDuplicate(Long ownerId, MemoryType type, String content) {
        return memoryMapper.selectOne(new LambdaQueryWrapper<Memory>()
                .eq(Memory::getUserId, ownerId)
                .eq(Memory::getMemoryType, type)
                .eq(Memory::getContent, content)
                .ne(Memory::getStatus, MemoryStatus.INVALID)
                .last("LIMIT 1"));
    }

    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String segment : query.trim().split("[\\s,，。;；:：、]+")) {
            String normalized = segment.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            String segmentValue = normalized.length() > 50 ? normalized.substring(0, 50) : normalized;
            tokens.add(segmentValue);
            if (containsChinese(normalized)) {
                // 中文没有空格，补充连续中文二元词，提升“订单连接数”这类查询的召回。
                for (int index = 0; index + 1 < normalized.length(); index++) {
                    if (isChinese(normalized.charAt(index)) && isChinese(normalized.charAt(index + 1))) {
                        tokens.add(normalized.substring(index, index + 2));
                    }
                    if (tokens.size() >= MAX_TOKENS) {
                        break;
                    }
                }
            }
            if (tokens.size() >= MAX_TOKENS) {
                break;
            }
        }
        return new ArrayList<>(tokens);
    }

    private boolean containsChinese(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (isChinese(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean isChinese(char character) {
        return character >= '一' && character <= '鿿';
    }

    private void requireOwner(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "归属用户编号必须为正整数");
        }
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "记忆内容不能为空");
        }
        String normalized = content.trim();
        if (normalized.length() > MAX_CONTENT_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "记忆内容不能超过 " + MAX_CONTENT_CHARS + " 个字符");
        }
        return normalized;
    }

    private String normalizeTitle(String title, String content) {
        String normalized = title == null || title.isBlank() ? content : title.trim();
        return normalized.length() <= MAX_TITLE_CHARS ? normalized : normalized.substring(0, MAX_TITLE_CHARS);
    }

    private String normalizeSummary(String summary, String content) {
        String normalized = summary == null || summary.isBlank() ? content : summary.trim();
        return normalized.length() <= MAX_SUMMARY_CHARS ? normalized : normalized.substring(0, MAX_SUMMARY_CHARS);
    }

    private String normalizeSourceConversation(String sourceConversationId, Long ownerId) {
        if (sourceConversationId == null || sourceConversationId.isBlank()) {
            return null;
        }
        String normalized = sourceConversationId.trim();
        if (!CONVERSATION_ID.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源会话标识格式不正确");
        }
        // 复用会话归属校验，防止把记忆挂到其他用户的会话上。
        conversationService.get(normalized, ownerId);
        return normalized;
    }

    private String normalizeTraceId(String sourceTraceId) {
        if (sourceTraceId == null || sourceTraceId.isBlank()) {
            return null;
        }
        String normalized = sourceTraceId.trim();
        if (normalized.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源 Trace ID 不能超过 64 个字符");
        }
        return normalized;
    }

    private double normalizeConfidence(double confidence) {
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "记忆置信度必须在 0 到 1 之间");
        }
        return confidence;
    }
}
