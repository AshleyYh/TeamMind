package com.teammind.web.controller;

import com.teammind.memory.application.MemoryService;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryStatus;
import com.teammind.memory.domain.MemoryType;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemoryControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemoryService memoryService;

    @Test
    void 新增记忆默认为草稿() throws Exception {
        when(memoryService.createDraft(anyLong(), any(), any(), anyString(), any(), any(), any(),
                anyDouble())).thenReturn(memory(MemoryStatus.DRAFT));

        mockMvc.perform(post("/api/v1/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":1,"memoryType":"FACT","content":"订单模块最大连接数为30",
                                 "confidence":0.9}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("10"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.memoryType").value("FACT"))
                .andExpect(jsonPath("$.data.content").value("订单模块最大连接数为30"));
    }

    @Test
    void 置信度越界和缺少内容被参数校验拒绝() throws Exception {
        mockMvc.perform(post("/api/v1/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":1,"memoryType":"FACT","content":"内容","confidence":1.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        mockMvc.perform(post("/api/v1/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":1,"memoryType":"FACT","content":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    @Test
    void 确认接口返回ACTIVE() throws Exception {
        when(memoryService.confirm(10L, 1L)).thenReturn(memory(MemoryStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/memories/10/confirm").param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void 列表和检索接口可用() throws Exception {
        when(memoryService.list(1L, MemoryStatus.ACTIVE, MemoryType.FACT, 50))
                .thenReturn(List.of(memory(MemoryStatus.ACTIVE)));
        when(memoryService.search(1L, "连接数", 5)).thenReturn(List.of(memory(MemoryStatus.ACTIVE)));

        mockMvc.perform(get("/api/v1/memories")
                        .param("ownerId", "1")
                        .param("status", "ACTIVE")
                        .param("type", "FACT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("10"));

        mockMvc.perform(get("/api/v1/memories/search")
                        .param("ownerId", "1")
                        .param("query", "连接数"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].content").value("订单模块最大连接数为30"));
    }

    @Test
    void 非法记忆状态返回参数错误() throws Exception {
        mockMvc.perform(get("/api/v1/memories")
                        .param("ownerId", "1")
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    private Memory memory(MemoryStatus status) {
        Memory memory = new Memory();
        memory.setId(10L);
        memory.setUserId(1L);
        memory.setMemoryType(MemoryType.FACT);
        memory.setTitle("订单连接数");
        memory.setContent("订单模块最大连接数为30");
        memory.setSummary("连接数为30");
        memory.setStatus(status);
        memory.setVersion(1);
        memory.setConfidence(0.9);
        memory.setDeleted(false);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }
}
