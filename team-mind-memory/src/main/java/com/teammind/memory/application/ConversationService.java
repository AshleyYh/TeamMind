package com.teammind.memory.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.memory.config.MemoryProperties;
import com.teammind.memory.domain.Conversation;
import com.teammind.memory.domain.ConversationSummary;
import com.teammind.memory.domain.StoredMessage;
import com.teammind.memory.mapper.ConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 会话元数据与 Redis 短期记忆。
 *
 * <p>数据库保存会话归属、标题和生命周期，Redis 保存最近消息与增量摘要。
 * conversationId 由客户端生成，必须同时匹配归属用户才能读写，避免跨用户读取会话。</p>
 */
@Service
public class ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String KEY_PREFIX = "teammind:memory:conversation:";
    private static final int MAX_CONTENT_CHARS = 8000;

    private final ConversationMapper conversationMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;

    public ConversationService(ConversationMapper conversationMapper, StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper, MemoryProperties properties) {
        this.conversationMapper = conversationMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 创建或获取会话元数据；标题只用于列表展示，首条消息可自动填充。 */
    @Transactional
    public Conversation ensure(String conversationId, Long ownerId, String title) {
        String normalizedId = normalizeId(conversationId);
        if (ownerId == null || ownerId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "归属用户编号必须为正整数");
        }
        Conversation conversation = findByConversationId(normalizedId);
        if (conversation != null) {
            assertOwner(conversation, ownerId);
            if ((conversation.getTitle() == null || conversation.getTitle().isBlank()
                    || "新会话".equals(conversation.getTitle()))
                    && title != null && !title.isBlank()) {
                conversation.setTitle(abbreviate(title.trim(), 255));
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(conversation);
            }
            return conversation;
        }
        Conversation created = new Conversation();
        created.setConversationId(normalizedId);
        created.setUserId(ownerId);
        created.setTitle(title == null || title.isBlank() ? "新会话" : abbreviate(title.trim(), 255));
        created.setDeleted(false);
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(LocalDateTime.now());
        try {
            conversationMapper.insert(created);
        } catch (DataIntegrityViolationException exception) {
            // 已逻辑删除的会话仍占用唯一 conversation_id，不能复用。
            throw new BusinessException(ErrorCode.CONFLICT, "会话标识已存在或已删除", exception);
        }
        return created;
    }

    public List<Conversation> list(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "归属用户编号必须为正整数");
        }
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, ownerId)
                .orderByDesc(Conversation::getUpdatedAt));
    }

    public Conversation get(String conversationId, Long ownerId) {
        Conversation conversation = findByConversationId(normalizeId(conversationId));
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在或不可访问");
        }
        assertOwner(conversation, ownerId);
        return conversation;
    }

    /** 逻辑删除会话元数据并清理 Redis；Redis 不可用时仍完成数据库删除。 */
    @Transactional
    public void delete(String conversationId, Long ownerId) {
        Conversation conversation = get(conversationId, ownerId);
        conversationMapper.deleteById(conversation.getId());
        try {
            redisTemplate.delete(List.of(messagesKey(conversation.getConversationId()),
                    summaryKey(conversation.getConversationId())));
        } catch (DataAccessException exception) {
            log.warn("会话已逻辑删除，但 Redis 清理失败：conversationId={}", conversation.getConversationId());
        }
    }

    public List<StoredMessage> loadMessages(String conversationId, Long ownerId, int limit) {
        Conversation conversation = get(conversationId, ownerId);
        int safeLimit = Math.min(Math.max(limit, 1), properties.maxMessages());
        try {
            List<String> raw = redisTemplate.opsForList()
                    .range(messagesKey(conversation.getConversationId()), -safeLimit, -1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<StoredMessage> messages = new ArrayList<>(raw.size());
            for (String item : raw) {
                StoredMessage message = deserialize(item);
                if (message != null && message.content() != null && !message.content().isBlank()) {
                    messages.add(message);
                }
            }
            return List.copyOf(messages);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "会话记忆服务暂不可用", exception);
        }
    }

    public int countMessages(String conversationId, Long ownerId) {
        Conversation conversation = get(conversationId, ownerId);
        try {
            Long size = redisTemplate.opsForList().size(messagesKey(conversation.getConversationId()));
            return size == null ? 0 : (int) Math.min(size, Integer.MAX_VALUE);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "会话记忆服务暂不可用", exception);
        }
    }

    public void appendMessages(String conversationId, Long ownerId, List<StoredMessage> messages) {
        Conversation conversation = get(conversationId, ownerId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<String> serialized = new ArrayList<>(messages.size());
        for (StoredMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()
                    || (!"user".equals(message.role()) && !"assistant".equals(message.role()))) {
                continue;
            }
            serialized.add(serialize(new StoredMessage(message.role(),
                    abbreviate(message.content(), MAX_CONTENT_CHARS), message.traceId(),
                    message.createdAt() == null ? LocalDateTime.now().toString() : message.createdAt())));
        }
        if (serialized.isEmpty()) {
            return;
        }
        String key = messagesKey(conversation.getConversationId());
        try {
            redisTemplate.opsForList().rightPushAll(key, serialized);
            redisTemplate.opsForList().trim(key, -properties.maxMessages(), -1);
            redisTemplate.expire(key, properties.conversationTtl());
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "会话记忆写入失败", exception);
        }
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    /** 摘要完成后裁掉已进入摘要的旧消息，保留最近窗口。 */
    public void trimOldest(String conversationId, Long ownerId, int keepLast) {
        Conversation conversation = get(conversationId, ownerId);
        String key = messagesKey(conversation.getConversationId());
        try {
            redisTemplate.opsForList().trim(key, -Math.max(keepLast, 1), -1);
            redisTemplate.expire(key, properties.conversationTtl());
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "会话记忆裁剪失败", exception);
        }
    }

    public ConversationSummary getSummary(String conversationId, Long ownerId) {
        Conversation conversation = get(conversationId, ownerId);
        try {
            String value = redisTemplate.opsForValue().get(summaryKey(conversation.getConversationId()));
            if (value == null || value.isBlank()) {
                return null;
            }
            return objectMapper.readValue(value, ConversationSummary.class);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "会话摘要读取失败", exception);
        } catch (JsonProcessingException exception) {
            log.warn("会话摘要格式损坏，已忽略：conversationId={}", conversation.getConversationId());
            return null;
        }
    }

    public void saveSummary(String conversationId, Long ownerId, ConversationSummary summary) {
        Conversation conversation = get(conversationId, ownerId);
        if (summary == null || summary.summary() == null || summary.summary().isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(summaryKey(conversation.getConversationId()),
                    serialize(summary), properties.conversationTtl());
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "会话摘要写入失败", exception);
        }
    }

    private Conversation findByConversationId(String conversationId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationId, conversationId));
    }

    private void assertOwner(Conversation conversation, Long ownerId) {
        if (ownerId == null || !conversation.getUserId().equals(ownerId)) {
            // 不区分“不存在”和“无权限”，避免枚举其他用户的会话。
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在或不可访问");
        }
    }

    private String normalizeId(String conversationId) {
        if (conversationId == null || !CONVERSATION_ID.matcher(conversationId).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "会话标识只能包含字母、数字、下划线和连字符，长度 1 到 64");
        }
        return conversationId;
    }

    private String messagesKey(String conversationId) {
        return KEY_PREFIX + conversationId + ":messages";
    }

    private String summaryKey(String conversationId) {
        return KEY_PREFIX + conversationId + ":summary";
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("会话消息序列化失败", exception);
        }
    }

    private StoredMessage deserialize(String value) {
        try {
            return objectMapper.readValue(value, StoredMessage.class);
        } catch (JsonProcessingException exception) {
            // 单条损坏消息不能阻断整个会话，记录后跳过。
            log.warn("Redis 会话消息格式损坏，已跳过单条消息");
            return null;
        }
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
