package com.teammind.ai.agent.memory;

import com.teammind.ai.agent.model.ConversationMessage;
import com.teammind.ai.model.ChatCompletionGateway;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.memory.application.ConversationService;
import com.teammind.memory.config.MemoryProperties;
import com.teammind.memory.domain.ConversationSummary;
import com.teammind.memory.domain.StoredMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationSummaryServiceTests {
    private final ConversationService conversationService = mock(ConversationService.class);
    private final ChatCompletionGateway chatGateway = mock(ChatCompletionGateway.class);
    private final MemoryProperties properties = new MemoryProperties(Duration.ofDays(7), 100, 20, 40,
            1000, 5, 5);
    private final ConversationSummaryService service = new ConversationSummaryService(
            conversationService, chatGateway, properties);

    @Test
    void 超过阈值时合并摘要并裁剪旧消息() {
        when(conversationService.loadMessages("conv_1", 1L, 100)).thenReturn(messages(50));
        when(conversationService.getSummary("conv_1", 1L)).thenReturn(null);
        when(chatGateway.generate(anyString(), anyString())).thenReturn("  合并后的摘要  ");

        ConversationContext context = service.load("conv_1", 1L, List.of());

        assertThat(context.summary()).isEqualTo("合并后的摘要");
        assertThat(context.messages()).hasSize(20);
        assertThat(context.warnings()).isEmpty();
        verify(conversationService).saveSummary(eq("conv_1"), eq(1L), argThat(summary ->
                summary.summarizedMessages() == 30 && summary.summary().equals("合并后的摘要")));
        verify(conversationService).trimOldest("conv_1", 1L, 20);
    }

    @Test
    void 未超过阈值时不调用摘要模型() {
        when(conversationService.loadMessages("conv_1", 1L, 100)).thenReturn(messages(10));
        when(conversationService.getSummary("conv_1", 1L)).thenReturn(null);

        ConversationContext context = service.load("conv_1", 1L, List.of());

        assertThat(context.messages()).hasSize(10);
        assertThat(context.summary()).isNull();
        verify(chatGateway, never()).generate(anyString(), anyString());
    }

    @Test
    void 摘要失败时保留历史并返回警告() {
        when(conversationService.loadMessages("conv_1", 1L, 100)).thenReturn(messages(50));
        when(conversationService.getSummary("conv_1", 1L)).thenReturn(null);
        when(chatGateway.generate(anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型不可用"));

        ConversationContext context = service.load("conv_1", 1L, List.of());

        assertThat(context.messages()).hasSize(50);
        assertThat(context.warnings()).anyMatch(warning -> warning.contains("摘要"));
        verify(conversationService, never()).trimOldest(anyString(), eq(1L), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 已有摘要会与新增旧消息合并() {
        when(conversationService.loadMessages("conv_1", 1L, 100)).thenReturn(messages(50));
        when(conversationService.getSummary("conv_1", 1L))
                .thenReturn(new ConversationSummary("旧摘要", 10, "2026-01-01T00:00:00"));
        when(chatGateway.generate(anyString(), org.mockito.ArgumentMatchers.contains("旧摘要")))
                .thenReturn("新摘要");

        ConversationContext context = service.load("conv_1", 1L, List.of());

        assertThat(context.summary()).isEqualTo("新摘要");
        verify(conversationService).saveSummary(eq("conv_1"), eq(1L), argThat(summary ->
                summary.summarizedMessages() == 40));
    }

    @Test
    void Redis不可用时降级为请求历史() {
        when(conversationService.loadMessages("conv_1", 1L, 100))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "redis down"));
        List<ConversationMessage> fallback = List.of(new ConversationMessage("user", "上一问"));

        ConversationContext context = service.load("conv_1", 1L, fallback);

        assertThat(context.messages()).containsExactlyElementsOf(fallback);
        assertThat(context.warnings()).anyMatch(warning -> warning.contains("会话记忆"));
    }

    @Test
    void 没有会话标识时直接使用请求历史() {
        ConversationContext context = service.load(null, 1L,
                List.of(new ConversationMessage("user", "上一问")));

        assertThat(context.summary()).isNull();
        assertThat(context.messages()).hasSize(1);
        verify(conversationService, never()).loadMessages(anyString(), eq(1L), org.mockito.ArgumentMatchers.anyInt());
    }

    private List<StoredMessage> messages(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new StoredMessage(index % 2 == 0 ? "user" : "assistant",
                        "消息 " + index, "trace", "2026-01-01T00:00:00"))
                .toList();
    }
}
