package com.teammind.ai.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.ai.model.ChatCompletionGateway;
import com.teammind.common.exception.BusinessException;
import com.teammind.memory.application.ConversationService;
import com.teammind.memory.application.MemoryService;
import com.teammind.memory.config.MemoryProperties;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryStatus;
import com.teammind.memory.domain.MemoryType;
import com.teammind.memory.domain.StoredMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractionServiceTests {
    private final ConversationService conversationService = mock(ConversationService.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final ChatCompletionGateway chatGateway = mock(ChatCompletionGateway.class);
    private final MemoryProperties properties = new MemoryProperties(Duration.ofDays(7), 200, 20, 40,
            2000, 5, 5);
    private final MemoryExtractionService service = new MemoryExtractionService(conversationService,
            memoryService, chatGateway, properties, new ObjectMapper());

    @Test
    void 提取合法候选并跳过非法候选() {
        when(conversationService.loadMessages("conv_1", 1L, 60)).thenReturn(messages());
        when(chatGateway.generate(anyString(), anyString())).thenReturn("""
                [
                  {"type":"FACT","title":"订单连接数","content":"订单模块最大连接数为30",
                   "summary":"连接数为30","confidence":0.9},
                  {"type":"UNKNOWN","content":"未知类型","confidence":0.9},
                  {"type":"FACT","content":"低置信度","confidence":0.2}
                ]
                """);
        when(memoryService.createDraft(anyLong(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyDouble())).thenReturn(memory());

        MemoryExtractionResult result = service.extract("conv_1", 1L);

        assertThat(result.memories()).hasSize(1);
        assertThat(result.warnings()).hasSize(2);
        ArgumentCaptor<MemoryType> typeCaptor = ArgumentCaptor.forClass(MemoryType.class);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> traceCaptor = ArgumentCaptor.forClass(String.class);
        verify(memoryService).createDraft(eq(1L), typeCaptor.capture(), anyString(),
                contentCaptor.capture(), anyString(), eq("conv_1"), traceCaptor.capture(), anyDouble());
        assertThat(typeCaptor.getValue()).isEqualTo(MemoryType.FACT);
        assertThat(contentCaptor.getValue()).contains("最大连接数为30");
        assertThat(traceCaptor.getValue()).isEqualTo("trace123");
    }

    @Test
    void 支持对象包裹的memories数组() {
        when(conversationService.loadMessages("conv_1", 1L, 60)).thenReturn(messages());
        when(chatGateway.generate(anyString(), anyString())).thenReturn("""
                {"memories":[{"type":"PREFERENCE","content":"回答尽量简洁","confidence":0.8}]}
                """);
        when(memoryService.createDraft(anyLong(), any(), any(), any(), any(), any(), any(), anyDouble()))
                .thenReturn(memory());

        MemoryExtractionResult result = service.extract("conv_1", 1L);

        assertThat(result.memories()).hasSize(1);
    }

    @Test
    void 非法JSON返回业务异常() {
        when(conversationService.loadMessages("conv_1", 1L, 60)).thenReturn(messages());
        when(chatGateway.generate(anyString(), anyString())).thenReturn("不是JSON");

        assertThatThrownBy(() -> service.extract("conv_1", 1L))
                .isInstanceOf(BusinessException.class);
        verify(memoryService, never()).createDraft(anyLong(), any(), any(), any(), any(), any(), any(),
                anyDouble());
    }

    @Test
    void 空会话不调用模型() {
        when(conversationService.loadMessages("conv_1", 1L, 60)).thenReturn(List.of());

        MemoryExtractionResult result = service.extract("conv_1", 1L);

        assertThat(result.memories()).isEmpty();
        verify(chatGateway, never()).generate(anyString(), anyString());
    }

    private List<StoredMessage> messages() {
        return List.of(
                new StoredMessage("user", "订单模块使用 HikariCP，最大连接数为 30。", "trace123",
                        "2026-01-01T00:00:00"),
                new StoredMessage("assistant", "已记录。", "trace123", "2026-01-01T00:00:01"));
    }

    private Memory memory() {
        Memory memory = new Memory();
        memory.setId(10L);
        memory.setUserId(1L);
        memory.setStatus(MemoryStatus.DRAFT);
        memory.setMemoryType(MemoryType.FACT);
        return memory;
    }
}
