package com.teammind.ai.agent.tool;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.ai.agent.model.ConversationMessage;
import com.teammind.ai.agent.tool.impl.ConversationHistoryTool;
import com.teammind.ai.agent.tool.impl.DocumentDetailTool;
import com.teammind.ai.agent.tool.impl.DocumentSearchTool;
import com.teammind.ai.agent.tool.impl.HistoryMemorySearchTool;
import com.teammind.ai.agent.tool.impl.KnowledgeSearchTool;
import com.teammind.knowledge.api.RetrievalResult;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.knowledge.application.DocumentService;
import com.teammind.knowledge.application.KnowledgeSearchService;
import com.teammind.knowledge.domain.Document;
import com.teammind.memory.application.MemoryService;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryStatus;
import com.teammind.memory.domain.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolsTests {
    private final KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final AgentProperties properties = properties();

    @Test
    void 知识库检索渲染带编号证据() {
        RetrievedChunk chunk = chunk(11L, 22L, 1L, "订单设计.pdf");
        when(searchService.searchWithTrace(anyString(), anyList(), anyLong(), anyInt(), any(), anyString()))
                .thenReturn(new RetrievalResult("trace", SearchStrategy.HYBRID, List.of(chunk)));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(searchService, properties);
        EvidenceCollector evidence = new EvidenceCollector(10);

        ToolResult result = tool.execute(context(List.of(1L), evidence), Map.of("query", "连接池"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("证据 [1]", "订单设计.pdf", "最大连接数为30");
        assertThat(evidence.citations()).singleElement()
                .satisfies(citation -> assertThat(citation.chunkId()).isEqualTo(11L));
    }

    @Test
    void 知识库无命中时返回空结果而不是异常() {
        when(searchService.searchWithTrace(anyString(), anyList(), anyLong(), anyInt(), any(), anyString()))
                .thenReturn(new RetrievalResult("trace", SearchStrategy.HYBRID, List.of()));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(searchService, properties);
        EvidenceCollector evidence = new EvidenceCollector(10);

        ToolResult result = tool.execute(context(List.of(1L), evidence), Map.of("query", "不存在的问题"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("没有检索到");
        assertThat(result.metadata()).containsEntry("count", 0);
        assertThat(evidence.citations()).isEmpty();
    }

    @Test
    void 重复证据按Chunk去重() {
        RetrievedChunk chunk = chunk(11L, 22L, 1L, "订单设计.pdf");
        when(searchService.searchWithTrace(anyString(), anyList(), anyLong(), anyInt(), any(), anyString()))
                .thenReturn(new RetrievalResult("trace", SearchStrategy.HYBRID, List.of(chunk, chunk)));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(searchService, properties);
        EvidenceCollector evidence = new EvidenceCollector(10);

        ToolResult result = tool.execute(context(List.of(1L), evidence), Map.of("query", "连接池"));

        assertThat(result.success()).isTrue();
        assertThat(evidence.citations()).hasSize(1);
    }

    @Test
    void 越界知识库编号被忽略并回退到请求范围() {
        when(searchService.searchWithTrace(anyString(), anyList(), anyLong(), anyInt(), any(), anyString()))
                .thenReturn(new RetrievalResult("trace", SearchStrategy.HYBRID, List.of()));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(searchService, properties);

        ToolResult outOfScope = tool.execute(context(List.of(1L), new EvidenceCollector(5)),
                Map.of("query", "连接池", "knowledgeBaseIds", List.of(2L)));
        ToolResult noScope = tool.execute(context(List.of(), new EvidenceCollector(5)),
                Map.of("query", "连接池"));

        assertThat(outOfScope.success()).isTrue();
        assertThat(outOfScope.content()).contains("已忽略", "2", "没有检索到");
        assertThat(outOfScope.metadata()).containsEntry("knowledgeBaseIds", List.of(1L));
        assertThat(outOfScope.metadata()).containsEntry("ignoredKnowledgeBaseIds", List.of(2L));
        verify(searchService).searchWithTrace(eq("连接池"), eq(List.of(1L)), eq(1L), eq(5), isNull(),
                eq("trace"));
        assertThat(noScope.success()).isFalse();
        assertThat(noScope.content()).contains("未指定可访问的知识库");
    }

    @Test
    void 模型抄错十九位知识库编号时仍回退到请求范围() {
        long allowed = 2125163621523984385L;
        long typo = 2125163621523984400L;
        when(searchService.searchWithTrace(anyString(), anyList(), anyLong(), anyInt(), any(), anyString()))
                .thenReturn(new RetrievalResult("trace", SearchStrategy.HYBRID, List.of()));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(searchService, properties);

        ToolResult result = tool.execute(context(List.of(allowed), new EvidenceCollector(5)),
                Map.of("query", "系安全带 前排后排", "knowledgeBaseIds", List.of(typo)));

        assertThat(result.success()).isTrue();
        assertThat(result.metadata()).containsEntry("knowledgeBaseIds", List.of(allowed));
        assertThat(result.metadata()).containsEntry("ignoredKnowledgeBaseIds", List.of(typo));
        verify(searchService).searchWithTrace(eq("系安全带 前排后排"), eq(List.of(allowed)), eq(1L),
                eq(5), isNull(), eq("trace"));
    }

    @Test
    void 文档详情不暴露服务器路径() {
        Document document = document(22L, 1L, "订单设计.pdf");
        document.setFilePath("/secret/internal/order-design.pdf");
        when(documentService.get(22L, 1L)).thenReturn(document);
        DocumentDetailTool tool = new DocumentDetailTool(documentService);

        ToolResult result = tool.execute(context(List.of(1L), new EvidenceCollector(5)),
                Map.of("documentId", 22));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("订单设计.pdf", "COMPLETED");
        assertThat(result.content()).doesNotContain("/secret/internal");
    }

    @Test
    void 文档详情对不存在文档返回失败观察() {
        when(documentService.get(anyLong(), anyLong()))
                .thenThrow(new com.teammind.common.exception.BusinessException("文档不存在"));
        DocumentDetailTool tool = new DocumentDetailTool(documentService);

        ToolResult result = tool.execute(context(List.of(1L), new EvidenceCollector(5)),
                Map.of("documentId", 99));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).isEqualTo("指定的文档不存在或不可访问。");
        assertThat(result.metadata()).containsEntry("error", "DOCUMENT_NOT_FOUND");
    }

    @Test
    void 指定文档检索按文档ID过滤() {
        Document first = document(22L, 1L, "订单设计.pdf");
        Document second = document(23L, 1L, "支付设计.pdf");
        when(documentService.get(22L, 1L)).thenReturn(first);
        when(documentService.get(23L, 1L)).thenReturn(second);
        RetrievedChunk orderChunk = chunk(11L, 22L, 1L, "订单设计.pdf");
        RetrievedChunk payChunk = chunk(12L, 23L, 1L, "支付设计.pdf");
        when(searchService.searchWithTrace(anyString(), anyList(), anyLong(), anyInt(), any(), anyString()))
                .thenReturn(new RetrievalResult("trace", SearchStrategy.HYBRID,
                        List.of(orderChunk, payChunk)));
        DocumentSearchTool tool = new DocumentSearchTool(searchService, documentService, properties);
        EvidenceCollector evidence = new EvidenceCollector(10);

        ToolResult result = tool.execute(context(List.of(1L), evidence),
                Map.of("query", "连接池", "documentIds", List.of(22)));

        assertThat(result.success()).isTrue();
        assertThat(result.metadata()).containsEntry("count", 1);
        assertThat(evidence.citations()).singleElement()
                .satisfies(citation -> assertThat(citation.documentId()).isEqualTo(22L));
    }

    @Test
    void 历史记忆工具返回已确认记忆() {
        when(memoryService.search(1L, "订单连接数", 5)).thenReturn(List.of(memory(1L)));
        HistoryMemorySearchTool tool = new HistoryMemorySearchTool(memoryService, properties);

        ToolResult result = tool.execute(context(List.of(1L), new EvidenceCollector(5)),
                Map.of("query", "订单连接数"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("记忆#1", "订单模块最大连接数为30", "FACT");
        assertThat(result.metadata()).containsEntry("count", 1);
    }

    @Test
    void 历史记忆工具无命中时返回空结果() {
        when(memoryService.search(1L, "不存在", 5)).thenReturn(List.of());
        HistoryMemorySearchTool tool = new HistoryMemorySearchTool(memoryService, properties);

        ToolResult result = tool.execute(context(List.of(1L), new EvidenceCollector(5)),
                Map.of("query", "不存在"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("没有找到");
        assertThat(result.metadata()).containsEntry("count", 0);
    }

    @Test
    void 会话历史工具只返回最近指定条数() {
        ConversationHistoryTool tool = new ConversationHistoryTool(properties);
        ToolExecutionContext context = new ToolExecutionContext("trace", 1L, List.of(1L), List.of(),
                List.of(new ConversationMessage("user", "第一问"),
                        new ConversationMessage("assistant", "第一答"),
                        new ConversationMessage("user", "第二问"),
                        new ConversationMessage("assistant", "第二答")),
                new EvidenceCollector(5), 5);

        ToolResult result = tool.execute(context, Map.of("limit", 2));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("第二问", "第二答");
        assertThat(result.content()).doesNotContain("第一问", "第一答");
    }

    private ToolExecutionContext context(List<Long> knowledgeBaseIds, EvidenceCollector evidence) {
        return new ToolExecutionContext("trace", 1L, knowledgeBaseIds, List.of(), List.of(), evidence, 5);
    }

    private RetrievedChunk chunk(Long chunkId, Long documentId, Long knowledgeBaseId, String name) {
        return new RetrievedChunk(chunkId, documentId, knowledgeBaseId, name, 1, 0, 3,
                "最大连接数为30", 0.9);
    }

    private Memory memory(Long id) {
        Memory memory = new Memory();
        memory.setId(id);
        memory.setUserId(1L);
        memory.setMemoryType(MemoryType.FACT);
        memory.setTitle("订单连接数");
        memory.setContent("订单模块最大连接数为30");
        memory.setSummary("订单连接数为30");
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setVersion(1);
        memory.setSourceConversationId("conv_1");
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }

    private Document document(Long id, Long knowledgeBaseId, String name) {
        Document document = new Document();
        document.setId(id);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setDocumentName(name);
        document.setFileType("pdf");
        document.setFilePath("/tmp/" + name);
        document.setCurrentVersion(1);
        document.setProcessingStatus("COMPLETED");
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        return document;
    }

    private AgentProperties properties() {
        return new AgentProperties(5, 8, 3, 12, 20, 4000, 6000, 1200,
                Duration.ofSeconds(90), Duration.ofSeconds(20));
    }
}
