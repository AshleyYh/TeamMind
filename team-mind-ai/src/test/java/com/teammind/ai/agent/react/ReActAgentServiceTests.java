package com.teammind.ai.agent.react;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.ai.agent.model.AgentModelGateway;
import com.teammind.ai.agent.model.AgentModelResponse;
import com.teammind.ai.agent.model.AgentToolCall;
import com.teammind.ai.agent.model.AgentToolSpec;
import com.teammind.ai.agent.model.ModelUsage;
import com.teammind.ai.agent.task.TaskType;
import com.teammind.ai.agent.task.TaskUnderstanding;
import com.teammind.ai.agent.task.TaskUnderstandingService;
import com.teammind.ai.agent.tool.ToolExecutionContext;
import com.teammind.ai.agent.tool.ToolRegistry;
import com.teammind.ai.agent.tool.ToolResult;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.EvidenceRecorder;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.application.KnowledgeBaseService;
import com.teammind.observability.application.RagTraceService;
import com.teammind.observability.domain.RagTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReActAgentServiceTests {
    private final TaskUnderstandingService understandingService = mock(TaskUnderstandingService.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final AgentModelGateway modelGateway = mock(AgentModelGateway.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final RagTraceService traceService = mock(RagTraceService.class);
    private final EvidenceRecorder evidenceRecorder = mock(EvidenceRecorder.class);
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    {
        RagTrace trace = new RagTrace();
        trace.setId(99L);
        when(traceService.start(anyString(), any(), anyString(), any(), anyString())).thenReturn(trace);
        when(toolRegistry.specifications()).thenReturn(List.of(
                new AgentToolSpec("knowledgeSearch", "检索知识库",
                        java.util.Map.of("type", "object", "properties", java.util.Map.of())),
                new AgentToolSpec("documentSearch", "指定文档检索",
                        java.util.Map.of("type", "object", "properties", java.util.Map.of()))));
    }

    @AfterEach
    void tearDown() {
        toolExecutor.close();
    }

    @Test
    void 普通对话不启用工具() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.GENERAL_CHAT));
        when(modelGateway.complete(any())).thenReturn(response("你好，我是 TeamMind。"));

        AgentAnswer answer = service(properties()).chat(request("你好", List.of(1L)));

        assertThat(answer.termination()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(answer.answer()).contains("TeamMind");
        assertThat(answer.steps()).isEmpty();
        assertThat(answer.citations()).isEmpty();
        verify(toolRegistry, never()).execute(anyString(), anyString(), any());
        verify(traceService).finish(eq(99L), eq(true), anyLong(), eq("你好，我是 TeamMind。"));
    }

    @Test
    void 连续执行多个工具并汇总引用() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any())).thenReturn(
                response(null, call("call_1", "knowledgeSearch", "{\"query\":\"连接池\"}"),
                        call("call_2", "documentSearch", "{\"query\":\"连接池\",\"documentIds\":[22]}")),
                response(null, call("call_3", "knowledgeSearch", "{\"query\":\"最大连接数\"}")),
                response("订单模块最大连接数为 30 [1]。"));
        when(toolRegistry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionContext context = invocation.getArgument(2);
            Integer index = context.evidence().add(chunk(11L));
            return ToolResult.success("证据 [" + index + "] 订单设计 最大连接数为30");
        });

        AgentAnswer answer = service(properties()).chat(request("最大连接数？", List.of(1L)));

        assertThat(answer.termination()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(answer.answer()).contains("[1]");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.steps()).filteredOn(step -> step.type() == AgentStepType.ACTION).hasSize(3);
        assertThat(answer.steps()).filteredOn(step -> step.type() == AgentStepType.OBSERVATION).hasSize(3);
        verify(toolRegistry, org.mockito.Mockito.times(3)).execute(anyString(), anyString(), any());
        verify(evidenceRecorder).markCitations(eq(answer.traceId()), eq(Set.of(11L)));
    }

    @Test
    void 工具失败作为观察继续执行() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any())).thenReturn(
                response(null, call("call_1", "knowledgeSearch", "{\"query\":\"连接池\"}")),
                response("工具暂时不可用，无法确认具体连接数。"));
        when(toolRegistry.execute(anyString(), anyString(), any()))
                .thenReturn(ToolResult.failure("检索服务暂时不可用。"));

        AgentAnswer answer = service(properties()).chat(request("最大连接数？", List.of(1L)));

        assertThat(answer.termination()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(answer.answer()).contains("无法确认");
        assertThat(answer.steps()).filteredOn(step -> step.type() == AgentStepType.OBSERVATION)
                .singleElement().satisfies(step -> assertThat(step.success()).isFalse());
    }

    @Test
    void 达到最大迭代次数后生成最终答案() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any())).thenReturn(
                response(null, call("call_1", "knowledgeSearch", "{\"query\":\"连接池\"}")),
                response("已检索到相关证据 [1]。"));
        when(toolRegistry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionContext context = invocation.getArgument(2);
            Integer index = context.evidence().add(chunk(11L));
            return ToolResult.success("证据 [" + index + "] 连接数为30");
        });

        AgentAnswer answer = service(properties(1, 8, 3, Duration.ofSeconds(2)))
                .chat(request("最大连接数？", List.of(1L)));

        assertThat(answer.termination()).isEqualTo(TerminationReason.MAX_ITERATIONS);
        assertThat(answer.answer()).contains("[1]");
        assertThat(answer.warnings()).anyMatch(warning -> warning.contains("最大推理轮数"));
        verify(modelGateway, org.mockito.Mockito.times(2)).complete(any());
    }

    @Test
    void 达到工具调用上限后停止执行() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any())).thenReturn(
                response(null, call("call_1", "knowledgeSearch", "{\"query\":\"a\"}"),
                        call("call_2", "knowledgeSearch", "{\"query\":\"b\"}")),
                response("已达到工具上限，基于现有证据回答 [1]。"));
        when(toolRegistry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionContext context = invocation.getArgument(2);
            Integer index = context.evidence().add(chunk(11L));
            return ToolResult.success("证据 [" + index + "] 连接数为30");
        });

        AgentAnswer answer = service(properties(5, 1, 3, Duration.ofSeconds(2)))
                .chat(request("最大连接数？", List.of(1L)));

        assertThat(answer.termination()).isEqualTo(TerminationReason.MAX_TOOL_CALLS);
        assertThat(answer.steps()).filteredOn(step -> step.type() == AgentStepType.ACTION).hasSize(1);
        verify(toolRegistry, org.mockito.Mockito.times(1)).execute(anyString(), anyString(), any());
    }

    @Test
    void 工具超时被转换为失败观察() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any())).thenReturn(
                response(null, call("call_1", "knowledgeSearch", "{\"query\":\"连接池\"}")),
                response("工具超时，未能获取证据。"));
        when(toolRegistry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("迟到的结果");
        });

        AgentAnswer answer = service(properties(5, 8, 3, Duration.ofMillis(100)))
                .chat(request("最大连接数？", List.of(1L)));

        assertThat(answer.termination()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(answer.steps()).filteredOn(step -> step.type() == AgentStepType.OBSERVATION)
                .singleElement().satisfies(step -> {
                    assertThat(step.success()).isFalse();
                    assertThat(step.content()).contains("超时");
                });
    }

    @Test
    void 连续工具失败达到上限后终止() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any())).thenReturn(
                response(null, call("call_1", "knowledgeSearch", "{\"query\":\"连接池\"}")),
                response("连续失败，无法完成检索。"));
        when(toolRegistry.execute(anyString(), anyString(), any()))
                .thenReturn(ToolResult.failure("工具不可用。"));

        AgentAnswer answer = service(properties(5, 8, 1, Duration.ofSeconds(2)))
                .chat(request("最大连接数？", List.of(1L)));

        assertThat(answer.termination()).isEqualTo(TerminationReason.TOOL_FAILURE_LIMIT);
        assertThat(answer.warnings()).anyMatch(warning -> warning.contains("连续工具调用失败"));
    }

    @Test
    void 越界引用被移除并记录警告() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any())).thenReturn(
                response(null, call("call_1", "knowledgeSearch", "{\"query\":\"连接池\"}")),
                response("订单模块最大连接数为 30 [2]。"));
        when(toolRegistry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionContext context = invocation.getArgument(2);
            Integer index = context.evidence().add(chunk(11L));
            return ToolResult.success("证据 [" + index + "] 连接数为30");
        });

        AgentAnswer answer = service(properties()).chat(request("最大连接数？", List.of(1L)));

        assertThat(answer.answer()).contains("30").doesNotContain("[2]");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.warnings()).anyMatch(warning -> warning.contains("无效引用"));
    }

    @Test
    void 流式模式按增量推送答案并保留引用() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any())).thenReturn(
                response(null, call("call_1", "knowledgeSearch", "{\"query\":\"连接池\"}")),
                response("订单模块最大连接数为 30 [1]。"));
        when(toolRegistry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionContext context = invocation.getArgument(2);
            Integer index = context.evidence().add(chunk(11L));
            return ToolResult.success("证据 [" + index + "] 连接数为30");
        });
        when(modelGateway.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("订单模块最大连接数为 30 ");
            consumer.accept("[1]。");
            return new AgentModelResponse("订单模块最大连接数为 30 [1]。", List.of(), "stop",
                    new ModelUsage(5, 6));
        });
        CapturingObserver observer = new CapturingObserver();

        AgentAnswer answer = service(properties()).stream(request("最大连接数？", List.of(1L)), observer);

        assertThat(observer.delta.toString()).isEqualTo("订单模块最大连接数为 30 [1]。");
        assertThat(answer.answer()).contains("[1]");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.steps()).isNotEmpty();
        verify(evidenceRecorder).markCitations(eq(answer.traceId()), eq(Set.of(11L)));
    }

    @Test
    void 工具执行后模型失败时使用降级回答() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any())).thenReturn(
                response(null, call("call_1", "knowledgeSearch", "{\"query\":\"连接池\"}")))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型不可用"));
        when(toolRegistry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionContext context = invocation.getArgument(2);
            Integer index = context.evidence().add(chunk(11L));
            return ToolResult.success("证据 [" + index + "] 连接数为30");
        });

        AgentAnswer answer = service(properties()).chat(request("最大连接数？", List.of(1L)));

        assertThat(answer.termination()).isEqualTo(TerminationReason.MODEL_ERROR);
        assertThat(answer.answer()).contains("未能生成完整答案");
        assertThat(answer.warnings()).anyMatch(warning -> warning.contains("模型调用失败"));
    }

    @Test
    void 无知识库访问权限时在调用模型前拒绝() {
        when(knowledgeBaseService.getAccessible(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在或不可访问"));

        assertThatThrownBy(() -> service(properties()).chat(request("最大连接数？", List.of(9L))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不可访问");
        verify(modelGateway, never()).complete(any());
        verify(traceService, never()).start(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void 首次模型失败直接向上返回业务异常() {
        when(understandingService.understand(any())).thenReturn(task(TaskType.KNOWLEDGE_QA));
        when(modelGateway.complete(any()))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型不可用"));

        assertThatThrownBy(() -> service(properties()).chat(request("最大连接数？", List.of(1L))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("模型不可用");
        verify(traceService).finish(eq(99L), eq(false), anyLong(), isNull());
    }

    private ReActAgentService service(AgentProperties properties) {
        return new ReActAgentService(understandingService, toolRegistry, modelGateway, properties,
                knowledgeBaseService, traceService, evidenceRecorder, toolExecutor);
    }

    private AgentExecutionRequest request(String message, List<Long> knowledgeBaseIds) {
        return new AgentExecutionRequest(message, knowledgeBaseIds, 1L, 5, "conv_1", List.of(), List.of());
    }

    private TaskUnderstanding task(TaskType type) {
        List<String> tools = type == TaskType.KNOWLEDGE_QA ? List.of("knowledgeSearch") : List.of();
        return new TaskUnderstanding(type, "测试意图", List.of(), List.of("连接池"), List.of(1L), null,
                tools, 0.9, false, List.of());
    }

    private AgentModelResponse response(String content, AgentToolCall... calls) {
        return new AgentModelResponse(content, List.of(calls), content == null ? "tool_calls" : "stop",
                new ModelUsage(10, 5));
    }

    private AgentToolCall call(String id, String name, String arguments) {
        return new AgentToolCall(id, name, arguments);
    }

    private RetrievedChunk chunk(Long chunkId) {
        return new RetrievedChunk(chunkId, 22L, 1L, "订单设计.pdf", 2, 0, 3,
                "最大连接数为30", 0.9);
    }

    private AgentProperties properties() {
        return properties(5, 8, 3, Duration.ofSeconds(20));
    }

    private AgentProperties properties(int maxIterations, int maxToolCalls, int maxConsecutiveFailures,
            Duration toolTimeout) {
        return new AgentProperties(maxIterations, maxToolCalls, maxConsecutiveFailures, 12, 20, 4000,
                6000, 1200, Duration.ofSeconds(30), toolTimeout);
    }

    private static class CapturingObserver implements AgentObserver {
        private final StringBuilder delta = new StringBuilder();
        private final List<AgentStep> steps = new ArrayList<>();

        @Override
        public void onStep(AgentStep step) {
            steps.add(step);
        }

        @Override
        public void onAnswerDelta(String value) {
            delta.append(value);
        }
    }
}
