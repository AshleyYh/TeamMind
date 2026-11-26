package com.teammind.web.controller;

import com.teammind.ai.agent.memory.MemoryExtractionResult;
import com.teammind.ai.agent.memory.MemoryExtractionService;
import com.teammind.memory.application.ConversationService;
import com.teammind.memory.domain.Conversation;
import com.teammind.memory.domain.ConversationSummary;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryStatus;
import com.teammind.memory.domain.MemoryType;
import com.teammind.memory.domain.StoredMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService conversationService;

    @MockitoBean
    private MemoryExtractionService memoryExtractionService;

    @Test
    void 查询会话消息返回摘要和最近消息() throws Exception {
        when(conversationService.get("conv_1", 1L)).thenReturn(conversation());
        when(conversationService.getSummary("conv_1", 1L))
                .thenReturn(new ConversationSummary("历史摘要", 2, "2026-01-01T00:00:00"));
        when(conversationService.loadMessages("conv_1", 1L, 50)).thenReturn(List.of(
                new StoredMessage("user", "你好", "trace123", "2026-01-01T00:00:00")));

        mockMvc.perform(get("/api/v1/conversations/conv_1/messages")
                        .param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("测试会话"))
                .andExpect(jsonPath("$.data.summary.summary").value("历史摘要"))
                .andExpect(jsonPath("$.data.messages[0].content").value("你好"));
    }

    @Test
    void 删除会话调用服务清理() throws Exception {
        mockMvc.perform(delete("/api/v1/conversations/conv_1").param("ownerId", "1"))
                .andExpect(status().isOk());

        verify(conversationService).delete("conv_1", 1L);
    }

    @Test
    void 从会话提取记忆返回草稿和警告() throws Exception {
        when(memoryExtractionService.extract("conv_1", 1L))
                .thenReturn(new MemoryExtractionResult(List.of(memory()), List.of("已跳过一条候选")));

        mockMvc.perform(post("/api/v1/conversations/conv_1/memories/extract")
                        .param("ownerId", "1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memories[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.memories[0].content").value("订单模块最大连接数为30"))
                .andExpect(jsonPath("$.data.warnings[0]").value("已跳过一条候选"));
    }

    @Test
    void 非法会话标识被路径校验拒绝() throws Exception {
        mockMvc.perform(get("/api/v1/conversations/bad id!/messages").param("ownerId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    private Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId(1L);
        conversation.setConversationId("conv_1");
        conversation.setUserId(1L);
        conversation.setTitle("测试会话");
        conversation.setDeleted(false);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private Memory memory() {
        Memory memory = new Memory();
        memory.setId(10L);
        memory.setUserId(1L);
        memory.setMemoryType(MemoryType.FACT);
        memory.setTitle("订单连接数");
        memory.setContent("订单模块最大连接数为30");
        memory.setStatus(MemoryStatus.DRAFT);
        memory.setVersion(1);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }
}
