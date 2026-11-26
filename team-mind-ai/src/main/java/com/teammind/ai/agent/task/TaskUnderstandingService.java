package com.teammind.ai.agent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.ai.agent.model.AgentMessage;
import com.teammind.ai.agent.model.AgentModelGateway;
import com.teammind.ai.agent.model.AgentModelRequest;
import com.teammind.ai.agent.model.AgentModelResponse;
import com.teammind.ai.agent.model.AgentToolSpec;
import com.teammind.ai.agent.prompt.AgentPrompts;
import com.teammind.ai.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 任务理解服务：规则预处理 -> LLM 结构化输出 -> 严格校验。
 * 模型不可用或返回非法 JSON 时降级为规则分类，并显式记录 warnings，不伪装成模型结果。
 */
@Service
public class TaskUnderstandingService {
    private static final Logger log = LoggerFactory.getLogger(TaskUnderstandingService.class);
    private static final double MIN_CONFIDENCE = 0.35;

    private final AgentModelGateway modelGateway;
    private final ToolRegistry toolRegistry;
    private final RulePreprocessor rulePreprocessor;
    private final ObjectMapper objectMapper;

    public TaskUnderstandingService(AgentModelGateway modelGateway, ToolRegistry toolRegistry,
            RulePreprocessor rulePreprocessor, ObjectMapper objectMapper) {
        this.modelGateway = modelGateway;
        this.toolRegistry = toolRegistry;
        this.rulePreprocessor = rulePreprocessor;
        this.objectMapper = objectMapper;
    }

    public TaskUnderstanding understand(TaskContext context) {
        RulePreprocessor.RuleResult rule = rulePreprocessor.preprocess(
                context.question(), context.allowedKnowledgeBaseIds());
        if (rule.fixedTaskType() != null) {
            return fixedTask(rule);
        }
        List<AgentToolSpec> tools = toolRegistry.specifications();
        try {
            AgentModelResponse response = modelGateway.complete(new AgentModelRequest(
                    AgentPrompts.taskSystemPrompt(tools),
                    List.of(AgentMessage.user(AgentPrompts.buildTaskUserContent(context))),
                    List.of(), true));
            return parse(context, rule, response.content(), response.usage());
        } catch (RuntimeException exception) {
            log.warn("任务理解模型不可用，使用规则降级：{}", exception.getMessage());
            return fallback(rule, "任务理解模型不可用或返回内容不合法，已使用规则分类");
        }
    }

    private TaskUnderstanding fixedTask(RulePreprocessor.RuleResult rule) {
        TaskType type = rule.fixedTaskType();
        String intent = switch (type) {
            case GENERAL_CHAT -> "普通对话";
            case HISTORY_QUERY -> "历史经验查询";
            case DOCUMENT_SEARCH -> "文档检索";
            default -> type.name();
        };
        return new TaskUnderstanding(type, intent, List.of(), List.of(), rule.knowledgeBaseIds(), null,
                defaultTools(type), 0.9, true, rule.warnings());
    }

    private TaskUnderstanding parse(TaskContext context, RulePreprocessor.RuleResult rule, String content,
            com.teammind.ai.agent.model.ModelUsage usage) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("任务理解结果为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(content));
        } catch (Exception exception) {
            throw new IllegalArgumentException("任务理解结果不是合法 JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("任务理解结果必须是 JSON 对象");
        }
        List<String> warnings = new ArrayList<>(rule.warnings());
        TaskType type = parseTaskType(text(root, "taskType"), warnings);
        String userIntent = text(root, "userIntent");
        if (userIntent.isBlank()) {
            userIntent = abbreviate(context.question(), 200);
        } else {
            userIntent = abbreviate(userIntent, 200);
        }
        List<String> entities = stringList(root.path("entities"), 10, 100, "entities", warnings);
        List<String> keywords = stringList(root.path("keywords"), 12, 100, "keywords", warnings);
        List<Long> knowledgeBaseIds = parseKnowledgeBaseIds(root.path("knowledgeBaseIds"), context,
                type, warnings);
        TimeRange timeRange = parseTimeRange(root.path("timeRange"), warnings);
        List<String> requiredTools = parseTools(root.path("requiredTools"), warnings);
        double confidence = parseConfidence(root.path("confidence"), warnings);
        if (requiredTools.isEmpty() && type != TaskType.GENERAL_CHAT && type != TaskType.UNKNOWN) {
            requiredTools = defaultTools(type);
        }
        if (type == TaskType.GENERAL_CHAT || type == TaskType.UNKNOWN) {
            requiredTools = List.of();
        }
        if (confidence < MIN_CONFIDENCE && type != TaskType.GENERAL_CHAT && type != TaskType.UNKNOWN) {
            warnings.add("任务分类置信度过低，已标记为 UNKNOWN");
            type = TaskType.UNKNOWN;
            requiredTools = List.of();
        }
        return new TaskUnderstanding(type, userIntent, entities, keywords, knowledgeBaseIds, timeRange,
                requiredTools, confidence, false, warnings, usage);
    }

    private TaskUnderstanding fallback(RulePreprocessor.RuleResult rule, String reason) {
        String question = rule.question();
        TaskType type;
        if (containsAny(question, "之前", "上次", "以前", "历史经验", "历史记录", "曾经")) {
            type = TaskType.HISTORY_QUERY;
        } else if (containsAny(question, "文档", "文件", "资料")
                && containsAny(question, "哪些", "查找", "搜索", "列出", "列表", "找")) {
            type = TaskType.DOCUMENT_SEARCH;
        } else if (!rule.knowledgeBaseIds().isEmpty()) {
            type = TaskType.KNOWLEDGE_QA;
        } else {
            type = TaskType.GENERAL_CHAT;
        }
        List<String> warnings = new ArrayList<>(rule.warnings());
        warnings.add(reason);
        return new TaskUnderstanding(type, abbreviate(question, 200), List.of(), List.of(),
                rule.knowledgeBaseIds(), null, defaultTools(type), 0.4, true, warnings);
    }

    private TaskType parseTaskType(String value, List<String> warnings) {
        if (value == null || value.isBlank()) {
            warnings.add("任务类型缺失，已标记为 UNKNOWN");
            return TaskType.UNKNOWN;
        }
        try {
            return TaskType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            warnings.add("未知任务类型 " + abbreviate(value, 30) + "，已标记为 UNKNOWN");
            return TaskType.UNKNOWN;
        }
    }

    private List<Long> parseKnowledgeBaseIds(JsonNode node, TaskContext context, TaskType type,
            List<String> warnings) {
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                Long value = parseLong(item);
                if (value == null) {
                    warnings.add("已忽略非法的知识库编号");
                } else {
                    values.add(value);
                }
            }
        } else if (!node.isMissingNode() && !node.isNull()) {
            warnings.add("知识库范围格式不正确，已使用请求范围");
        }
        List<Long> allowed = context.allowedKnowledgeBaseIds();
        if (allowed.isEmpty()) {
            if (!values.isEmpty()) {
                warnings.add("当前请求没有可访问的知识库，已忽略模型返回的知识库范围");
            }
            return List.of();
        }
        Set<Long> allowedSet = Set.copyOf(allowed);
        List<Long> retained = values.stream().filter(allowedSet::contains).toList();
        if (retained.size() < values.size()) {
            warnings.add("已忽略超出访问范围的知识库编号");
        }
        if (retained.isEmpty() && (type == TaskType.KNOWLEDGE_QA || type == TaskType.DOCUMENT_SEARCH
                || type == TaskType.HISTORY_QUERY)) {
            return List.copyOf(allowed);
        }
        return retained;
    }

    private TimeRange parseTimeRange(JsonNode node, List<String> warnings) {
        if (node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return null;
        }
        if (!node.isObject()) {
            warnings.add("时间范围格式不正确，已忽略");
            return null;
        }
        String start = node.hasNonNull("start") ? node.get("start").asText("").trim() : null;
        String end = node.hasNonNull("end") ? node.get("end").asText("").trim() : null;
        LocalDate startDate = parseDate(start);
        LocalDate endDate = parseDate(end);
        if ((start != null && !start.isBlank() && startDate == null)
                || (end != null && !end.isBlank() && endDate == null)) {
            warnings.add("时间范围不是合法的 yyyy-MM-dd 日期，已忽略");
            return null;
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            warnings.add("时间范围开始日期晚于结束日期，已忽略");
            return null;
        }
        if (startDate == null && endDate == null) {
            return null;
        }
        return new TimeRange(startDate == null ? null : startDate.toString(),
                endDate == null ? null : endDate.toString());
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private List<String> parseTools(JsonNode node, List<String> warnings) {
        if (!node.isArray()) {
            if (!node.isMissingNode() && !node.isNull()) {
                warnings.add("工具列表格式不正确，已使用任务类型默认工具");
            }
            return List.of();
        }
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                warnings.add("已忽略非法的工具名称");
                continue;
            }
            String name = item.asText().trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!toolRegistry.contains(name)) {
                warnings.add("已忽略未注册的工具 " + abbreviate(name, 40));
                continue;
            }
            tools.add(name);
        }
        return List.copyOf(tools);
    }

    private double parseConfidence(JsonNode node, List<String> warnings) {
        if (node.isMissingNode() || node.isNull()) {
            warnings.add("任务置信度缺失，按 0 处理");
            return 0;
        }
        double value;
        if (node.isNumber()) {
            value = node.asDouble();
        } else if (node.isTextual()) {
            try {
                value = Double.parseDouble(node.asText().trim());
            } catch (NumberFormatException exception) {
                warnings.add("任务置信度不是数字，按 0 处理");
                return 0;
            }
        } else {
            warnings.add("任务置信度格式不正确，按 0 处理");
            return 0;
        }
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            warnings.add("任务置信度必须在 0 到 1 之间，按 0 处理");
            return 0;
        }
        return value;
    }

    private List<String> stringList(JsonNode node, int maxSize, int maxLength, String field,
            List<String> warnings) {
        if (!node.isArray()) {
            if (!node.isMissingNode() && !node.isNull()) {
                warnings.add(field + " 格式不正确，已忽略");
            }
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                continue;
            }
            String value = item.asText().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (values.size() >= maxSize) {
                warnings.add(field + " 超过 " + maxSize + " 项，已截断");
                break;
            }
            values.add(abbreviate(value, maxLength));
        }
        return List.copyOf(values);
    }

    private Long parseLong(JsonNode node) {
        if (node.isNumber() && node.canConvertToLong()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private List<String> defaultTools(TaskType type) {
        return switch (type) {
            case KNOWLEDGE_QA -> List.of("knowledgeSearch");
            case DOCUMENT_SEARCH -> List.of("documentSearch");
            case HISTORY_QUERY -> List.of("historyMemorySearch", "knowledgeSearch");
            case GENERAL_CHAT, UNKNOWN -> List.of();
        };
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isTextual() ? node.asText().trim() : "";
    }

    private String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return trimmed;
        }
        String withoutStart = trimmed.substring(firstLineEnd + 1);
        int fenceEnd = withoutStart.lastIndexOf("```");
        return fenceEnd < 0 ? withoutStart.trim() : withoutStart.substring(0, fenceEnd).trim();
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
