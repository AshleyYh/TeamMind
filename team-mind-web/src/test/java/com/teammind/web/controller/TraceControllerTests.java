package com.teammind.web.controller;

import com.teammind.observability.application.EvidenceService;
import com.teammind.observability.application.RagTraceService;
import com.teammind.observability.domain.Evidence;
import com.teammind.observability.domain.RagTrace;
import com.teammind.observability.domain.TraceSpan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TraceControllerTests {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagTraceService traceService;

    @MockitoBean
    private EvidenceService evidenceService;

    @Test
    void 查询Trace列表和详情() throws Exception {
        when(traceService.list(1L, 20)).thenReturn(List.of(trace()));
        when(traceService.get(TRACE_ID, 1L)).thenReturn(trace());
        when(traceService.spans(TRACE_ID, 1L)).thenReturn(List.of(span()));

        mockMvc.perform(get("/api/v1/traces").param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.data[0].totalTokens").value(15));

        mockMvc.perform(get("/api/v1/traces/" + TRACE_ID).param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trace.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.spans[0].spanType").value("TOOL_CALL"));
    }

    @Test
    void 查询Span和Evidence() throws Exception {
        when(traceService.get(TRACE_ID, 1L)).thenReturn(trace());
        when(traceService.spans(TRACE_ID, 1L)).thenReturn(List.of(span()));
        Evidence evidence = new Evidence();
        evidence.setId(1L);
        evidence.setTraceId(TRACE_ID);
        evidence.setOwnerId(1L);
        evidence.setDocumentName("订单设计.pdf");
        when(evidenceService.list(TRACE_ID, 1L)).thenReturn(List.of(evidence));

        mockMvc.perform(get("/api/v1/traces/" + TRACE_ID + "/spans").param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/traces/" + TRACE_ID + "/evidences").param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].documentName").value("订单设计.pdf"));
    }

    @Test
    void 非法TraceId返回参数错误() throws Exception {
        mockMvc.perform(get("/api/v1/traces/not-a-trace").param("ownerId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    private RagTrace trace() {
        RagTrace trace = new RagTrace();
        trace.setId(99L);
        trace.setTraceId(TRACE_ID);
        trace.setUserId(1L);
        trace.setStatus("SUCCESS");
        trace.setTotalDuration(120L);
        trace.setTotalTokens(15L);
        trace.setPromptTokens(10L);
        trace.setCompletionTokens(5L);
        trace.setStrategy("AGENT:KNOWLEDGE_QA");
        trace.setCreatedAt(LocalDateTime.now());
        return trace;
    }

    private TraceSpan span() {
        TraceSpan span = new TraceSpan();
        span.setId(1L);
        span.setTraceId(TRACE_ID);
        span.setSpanId("span-1");
        span.setSpanType("TOOL_CALL");
        span.setStatus("SUCCESS");
        span.setDuration(10L);
        return span;
    }
}
