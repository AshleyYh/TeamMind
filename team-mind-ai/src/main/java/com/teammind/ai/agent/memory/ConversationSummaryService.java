package com.teammind.ai.agent.memory;

import com.teammind.ai.agent.model.ConversationMessage;
import com.teammind.ai.model.ChatCompletionGateway;
import com.teammind.ai.prompt.MemoryPrompts;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.memory.application.ConversationService;
import com.teammind.memory.config.MemoryProperties;
import com.teammind.memory.domain.ConversationSummary;
import com.teammind.memory.domain.StoredMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话上下文加载与增量摘要。
 *
 * <p>当 Redis 消息超过摘要阈值时，把超出最近窗口的旧消息合并进已有摘要，
 * 然后裁剪 Redis；这样提示词长度有界，旧信息仍以摘要形式保留。
 * Redis 或摘要模型失败时降级为请求携带的历史，不阻断 Agent 主流程。</p>
 */
@Service
public class ConversationSummaryService {
    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    private final ConversationService conversationService;
    private final ChatCompletionGateway chatGateway;
    private final MemoryProperties properties;

    public ConversationSummaryService(ConversationService conversationService,
            ChatCompletionGateway chatGateway, MemoryProperties properties) {
        this.conversationService = conversationService;
        this.chatGateway = chatGateway;
        this.properties = properties;
    }

    public ConversationContext load(String conversationId, Long ownerId,
            List<ConversationMessage> fallbackHistory) {
        if (conversationId == null || conversationId.isBlank()) {
            return ConversationContext.fallback(null, fallbackHistory);
        }
        List<String> warnings = new ArrayList<>();
        try {
            List<StoredMessage> stored = conversationService.loadMessages(conversationId, ownerId,
                    properties.maxMessages());
            if (stored.isEmpty()) {
                return new ConversationContext(conversationId, null, fallbackHistory, List.of());
            }
            ConversationSummary existing = conversationService.getSummary(conversationId, ownerId);
            String summary = existing == null ? null : existing.summary();
            int summarizedMessages = existing == null ? 0 : existing.summarizedMessages();
            int contextMessages = properties.contextMessages();

            if (stored.size() > properties.summaryThreshold() && stored.size() > contextMessages) {
                int oldCount = stored.size() - contextMessages;
                List<StoredMessage> oldMessages = stored.subList(0, oldCount);
                try {
                    String merged = chatGateway.generate(MemoryPrompts.SUMMARY_SYSTEM_PROMPT,
                            MemoryPrompts.buildSummaryContent(summary, oldMessages));
                    if (merged == null || merged.isBlank()) {
                        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "摘要模型未返回有效内容");
                    }
                    summary = abbreviate(merged.trim(), properties.maxSummaryChars());
                    summarizedMessages += oldCount;
                    conversationService.saveSummary(conversationId, ownerId,
                            new ConversationSummary(summary, summarizedMessages, LocalDateTime.now().toString()));
                    conversationService.trimOldest(conversationId, ownerId, contextMessages);
                    stored = stored.subList(oldCount, stored.size());
                } catch (RuntimeException exception) {
                    // 摘要失败时保留原始消息，下一次请求继续尝试，不丢历史。
                    log.warn("会话增量摘要失败，保留完整历史：conversationId={}", conversationId);
                    warnings.add("会话摘要暂时失败，已保留完整历史消息。");
                }
            }
            List<ConversationMessage> messages = stored.stream()
                    .map(message -> new ConversationMessage(message.role(), message.content()))
                    .toList();
            return new ConversationContext(conversationId, summary, messages, warnings);
        } catch (BusinessException exception) {
            log.warn("会话记忆读取失败，已降级为请求历史：conversationId={}", conversationId);
            warnings.add("会话记忆暂不可用，本次仅使用请求携带的历史。");
            return new ConversationContext(conversationId, null, fallbackHistory, warnings);
        }
    }

    private String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
