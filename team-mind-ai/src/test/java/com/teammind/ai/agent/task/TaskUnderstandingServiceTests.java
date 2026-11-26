package com.teammind.ai.agent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.ai.agent.model.AgentModelGateway;
import com.teammind.ai.agent.model.AgentModelResponse;
import com.teammind.ai.agent.model.ConversationMessage;
import com.teammind.ai.agent.model.ModelUsage;
import com.teammind.ai.agent.tool.AgentTool;
import com.teammind.ai.agent.tool.ToolExecutionContext;
import com.teammind.ai.agent.tool.ToolRegistry;
import com.teammind.ai.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskUnderstandingServiceTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentModelGateway gateway = mock(AgentModelGateway.class);
    private final ToolRegistry registry = new ToolRegistry(List.of(
            tool("knowledgeSearch"), tool("documentSearch"), tool("historyMemorySearch"),
            tool("getDocumentDetail"), tool("getConversationHistory")), objectMapper);
    private final TaskUnderstandingService service = new TaskUnderstandingService(
            gateway, registry, new RulePreprocessor(properties()), objectMapper);

    @Test
    void 解析结构化任务并过滤越界知识库和未知工具() {
        when(gateway.complete(any())).thenReturn(response("""
                {"taskType":"KNOWLEDGE_QA","userIntent":"查询连接池","entities":["订单模块"],
                 "keywords":["连接池","最大连接数"],"knowledgeBaseIds":[1,9],
                 "timeRange":{"start":"2026-01-01","end":"2026-01-31"},
                 "requiredTools":["knowledgeSearch","shell"],"confidence":0.88}
                """));

        TaskUnderstanding result = service.understand(context("最大连接数是多少？", List.of(1L, 2L)));

        assertThat(result.taskType()).isEqualTo(TaskType.KNOWLEDGE_QA);
        assertThat(result.knowledgeBaseIds()).containsExactly(1L);
        assertThat(result.requiredTools()).containsExactly("knowledgeSearch");
        assertThat(result.timeRange()).isNotNull();
        assertThat(result.timeRange().start()).isEqualTo("2026-01-01");
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("超出访问范围"));
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("未注册的工具"));
        assertThat(result.ruleBased()).isFalse();
    }

    @Test
    void 非法JSON降级为规则分类并标记警告() {
        when(gateway.complete(any())).thenReturn(response("这不是 JSON"));

        TaskUnderstanding result = service.understand(context("最大连接数是多少？", List.of(1L)));

        assertThat(result.taskType()).isEqualTo(TaskType.KNOWLEDGE_QA);
        assertThat(result.requiredTools()).containsExactly("knowledgeSearch");
        assertThat(result.ruleBased()).isTrue();
        assertThat(result.confidence()).isEqualTo(0.4);
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("规则分类"));
    }

    @Test
    void 低置信度和未知任务类型都归为UNKNOWN() {
        when(gateway.complete(any())).thenReturn(response("""
                {"taskType":"KNOWLEDGE_QA","userIntent":"查询","entities":[],"keywords":[],
                 "knowledgeBaseIds":[1],"timeRange":null,"requiredTools":["knowledgeSearch"],"confidence":0.2}
                """));
        TaskUnderstanding lowConfidence = service.understand(context("这个问题是什么？", List.of(1L)));
        assertThat(lowConfidence.taskType()).isEqualTo(TaskType.UNKNOWN);
        assertThat(lowConfidence.requiredTools()).isEmpty();

        when(gateway.complete(any())).thenReturn(response("""
                {"taskType":"OPEN_SHELL","userIntent":"执行命令","entities":[],"keywords":[],
                 "knowledgeBaseIds":[],"timeRange":null,"requiredTools":["shell"],"confidence":0.9}
                """));
        TaskUnderstanding unknownType = service.understand(context("执行命令", List.of(1L)));
        assertThat(unknownType.taskType()).isEqualTo(TaskType.UNKNOWN);
        assertThat(unknownType.requiredTools()).isEmpty();
        assertThat(unknownType.warnings()).anyMatch(warning -> warning.contains("未知任务类型"));
    }

    @Test
    void 非法时间范围被置空并记录警告() {
        when(gateway.complete(any())).thenReturn(response("""
                {"taskType":"HISTORY_QUERY","userIntent":"查历史","entities":[],"keywords":[],
                 "knowledgeBaseIds":[1],"timeRange":{"start":"2026-13-40","end":"2026-01-01"},
                 "requiredTools":["historyMemorySearch"],"confidence":0.9}
                """));

        TaskUnderstanding result = service.understand(context("查询 2026 年 1 月的技术决策记录", List.of(1L)));

        assertThat(result.timeRange()).isNull();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("时间范围"));
    }

    @Test
    void 固定问候意图不调用模型() {
        TaskUnderstanding result = service.understand(context("你好", List.of(1L)));

        assertThat(result.taskType()).isEqualTo(TaskType.GENERAL_CHAT);
        assertThat(result.ruleBased()).isTrue();
        assertThat(result.requiredTools()).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void 模型返回代码块包裹的JSON也能解析() {
        when(gateway.complete(any())).thenReturn(response("""
                ```json
                {"taskType":"DOCUMENT_SEARCH","userIntent":"查找文档","entities":[],"keywords":["文档"],
                 "knowledgeBaseIds":[1],"timeRange":null,"requiredTools":["documentSearch"],"confidence":0.9}
                ```
                """));

        TaskUnderstanding result = service.understand(context("订单设计文档里写了什么内容？", List.of(1L)));

        assertThat(result.taskType()).isEqualTo(TaskType.DOCUMENT_SEARCH);
        assertThat(result.requiredTools()).containsExactly("documentSearch");
    }

    private TaskContext context(String question, List<Long> knowledgeBaseIds) {
        return new TaskContext(question, 1L, knowledgeBaseIds, List.of(),
                List.of(new ConversationMessage("user", "之前的连接数是多少？")));
    }

    private AgentModelResponse response(String content) {
        return new AgentModelResponse(content, List.of(), "stop", ModelUsage.empty());
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试工具";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
                return ToolResult.success("ok");
            }
        };
    }

    private AgentProperties properties() {
        return new AgentProperties(5, 8, 3, 12, 20, 4000, 6000, 1200,
                Duration.ofSeconds(90), Duration.ofSeconds(20));
    }
}
