package com.teammind.ai.agent.task;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则预处理只处理确定性工作：空输入、控制字符、长度、固定意图和显式知识库范围。
 * 复杂语义留给 LLM，规则层不猜测用户意图。
 */
@Component
public class RulePreprocessor {
    private static final Pattern GREETING = Pattern.compile(
            "^(你好|您好|嗨|hi|hello|hey|在吗|早上好|下午好|晚上好)[!！。，,.\\s]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY_INTENT = Pattern.compile(
            "(之前|上次|以前|历史经验|历史记录|我们讨论过|曾经).{0,30}(怎么|如何|为什么|解决|问题|经验|结论|讨论|记录)");
    private static final Pattern DOCUMENT_SEARCH_INTENT = Pattern.compile(
            "(哪些|什么|查找|搜索|列出|找一下|找找|有没有).{0,12}(文档|文件|资料)|文档列表|资料清单");
    private static final Pattern DOCUMENT_ID_SCOPE = Pattern.compile(
            "(?:只在|仅在|限定于|限于|指定)\\s*知识库\\s*(?:id|编号)?\\s*[:：#]?\\s*((?:\\d+[\\s,，、和]*)+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_KNOWLEDGE_BASE_SCOPE = Pattern.compile(
            "(?:知识库|knowledge\\s*base)\\s*(?:id|编号)\\s*[:：#]?\\s*((?:\\d+[\\s,，、和]*)+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLON_KNOWLEDGE_BASE_SCOPE = Pattern.compile(
            "(?:知识库|knowledge\\s*base)\\s*[:：]\\s*((?:\\d+[\\s,，、和]*)+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDINAL_KNOWLEDGE_BASE_SCOPE = Pattern.compile(
            "第\\s*(\\d+)\\s*个?\\s*知识库");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final AgentProperties properties;

    public RulePreprocessor(AgentProperties properties) {
        this.properties = properties;
    }

    public RuleResult preprocess(String rawQuestion, List<Long> allowedKnowledgeBaseIds) {
        String question = normalize(rawQuestion);
        if (question.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "问题不能为空");
        }
        if (question.length() > properties.maxQuestionLength()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "问题长度不能超过 " + properties.maxQuestionLength() + " 个字符");
        }
        List<String> warnings = new ArrayList<>();
        List<Long> scope = resolveKnowledgeBaseScope(question, allowedKnowledgeBaseIds, warnings);
        TaskType fixedTaskType = detectFixedTaskType(question);
        return new RuleResult(question, scope, fixedTaskType, warnings);
    }

    private String normalize(String rawQuestion) {
        if (rawQuestion == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(rawQuestion.length());
        for (int index = 0; index < rawQuestion.length(); index++) {
            char value = rawQuestion.charAt(index);
            if (value == '\n' || value == '\t') {
                normalized.append(value);
            } else if (Character.isISOControl(value)) {
                // 控制字符可能被用于伪造日志或干扰模型输入，统一替换为空格。
                normalized.append(' ');
            } else {
                normalized.append(value);
            }
        }
        return normalized.toString().replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private TaskType detectFixedTaskType(String question) {
        String normalized = question.trim();
        if (GREETING.matcher(normalized).matches()) {
            return TaskType.GENERAL_CHAT;
        }
        if (HISTORY_INTENT.matcher(normalized).find()) {
            return TaskType.HISTORY_QUERY;
        }
        if (DOCUMENT_SEARCH_INTENT.matcher(normalized).find()) {
            return TaskType.DOCUMENT_SEARCH;
        }
        return null;
    }

    private List<Long> resolveKnowledgeBaseScope(String question, List<Long> allowedKnowledgeBaseIds,
            List<String> warnings) {
        Set<Long> requested = new LinkedHashSet<>();
        collectIds(question, DOCUMENT_ID_SCOPE, requested);
        collectIds(question, NAMED_KNOWLEDGE_BASE_SCOPE, requested);
        collectIds(question, COLON_KNOWLEDGE_BASE_SCOPE, requested);
        Matcher ordinal = ORDINAL_KNOWLEDGE_BASE_SCOPE.matcher(question);
        while (ordinal.find()) {
            try {
                requested.add(Long.parseLong(ordinal.group(1)));
            } catch (NumberFormatException ignored) {
                // 超长数字不是合法编号，忽略后由范围校验统一处理。
            }
        }
        List<Long> allowed = allowedKnowledgeBaseIds == null ? List.of() : allowedKnowledgeBaseIds;
        if (requested.isEmpty()) {
            return List.copyOf(allowed);
        }
        if (allowed.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "当前请求没有可访问的知识库");
        }
        Set<Long> allowedSet = Set.copyOf(allowed);
        List<Long> retained = requested.stream().filter(allowedSet::contains).toList();
        if (retained.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "指定的知识库不存在或不可访问");
        }
        if (retained.size() < requested.size()) {
            warnings.add("已忽略超出访问范围的知识库编号");
        }
        return retained;
    }

    private void collectIds(String question, Pattern pattern, Set<Long> target) {
        Matcher matcher = pattern.matcher(question);
        while (matcher.find()) {
            Matcher digits = DIGITS.matcher(matcher.group(1));
            while (digits.find()) {
                try {
                    target.add(Long.parseLong(digits.group()));
                } catch (NumberFormatException ignored) {
                    // 超长数字不是合法编号，直接忽略，交给后续范围校验。
                }
            }
        }
    }

    /**
     * @param question        清洗后的问题
     * @param knowledgeBaseIds 经过访问范围校验的最终知识库范围
     * @param fixedTaskType   规则层可以确定的固定任务类型，null 表示需要 LLM 分类
     */
    public record RuleResult(String question, List<Long> knowledgeBaseIds,
            TaskType fixedTaskType, List<String> warnings) {

        public RuleResult {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
