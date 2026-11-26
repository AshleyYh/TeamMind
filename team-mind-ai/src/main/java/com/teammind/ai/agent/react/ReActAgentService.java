package com.teammind.ai.agent.react;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.ai.agent.model.AgentMessage;
import com.teammind.ai.agent.model.AgentModelGateway;
import com.teammind.ai.agent.model.AgentModelRequest;
import com.teammind.ai.agent.model.AgentModelResponse;
import com.teammind.ai.agent.model.AgentToolCall;
import com.teammind.ai.agent.model.AgentToolSpec;
import com.teammind.ai.agent.model.ConversationMessage;
import com.teammind.ai.agent.memory.ConversationContext;
import com.teammind.ai.agent.memory.ConversationSummaryService;
import com.teammind.ai.agent.model.ModelUsage;
import com.teammind.ai.agent.prompt.AgentPrompts;
import com.teammind.ai.agent.task.TaskContext;
import com.teammind.ai.agent.task.TaskUnderstanding;
import com.teammind.ai.agent.task.TaskUnderstandingService;
import com.teammind.ai.agent.tool.EvidenceCollector;
import com.teammind.ai.agent.tool.ToolExecutionContext;
import com.teammind.ai.agent.tool.ToolRegistry;
import com.teammind.ai.agent.tool.ToolResult;
import com.teammind.ai.api.CitationStreamSanitizer;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.EvidenceRecorder;
import com.teammind.knowledge.application.KnowledgeBaseService;
import com.teammind.memory.application.ConversationService;
import com.teammind.memory.domain.StoredMessage;
import com.teammind.observability.application.RagTraceService;
import com.teammind.observability.application.RagTraceService.SpanHandle;
import com.teammind.observability.domain.RagTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 可控的 ReAct Agent 执行循环。
 *
 * <p>执行顺序：规则预处理 -> 结构化任务理解 -> 多轮 Thought/Action/Observation -> 最终答案。
 * 工具只能通过注册表按名称调用，参数和访问范围由工具层再次校验。循环具有最大轮数、最大工具次数、
 * 整体截止时间和连续失败上限，任何终止条件都会转成最终答案而不是静默截断。</p>
 */
@Service
public class ReActAgentService {
    private static final Logger log = LoggerFactory.getLogger(ReActAgentService.class);
    private static final int MAX_TOOL_ARGUMENT_CHARS = 20000;
    private static final int STEP_CONTENT_CHARS = 1200;

    private final TaskUnderstandingService understandingService;
    private final ToolRegistry toolRegistry;
    private final AgentModelGateway modelGateway;
    private final AgentProperties properties;
    private final KnowledgeBaseService knowledgeBaseService;
    private final RagTraceService traceService;
    private final EvidenceRecorder evidenceRecorder;
    private final ConversationService conversationService;
    private final ConversationSummaryService conversationSummaryService;
    private final ExecutorService toolExecutor;

    public ReActAgentService(TaskUnderstandingService understandingService, ToolRegistry toolRegistry,
            AgentModelGateway modelGateway, AgentProperties properties,
            KnowledgeBaseService knowledgeBaseService, RagTraceService traceService,
            EvidenceRecorder evidenceRecorder, ConversationService conversationService,
            ConversationSummaryService conversationSummaryService,
            @Qualifier("agentToolExecutor") ExecutorService toolExecutor) {
        this.understandingService = understandingService;
        this.toolRegistry = toolRegistry;
        this.modelGateway = modelGateway;
        this.properties = properties;
        this.knowledgeBaseService = knowledgeBaseService;
        this.traceService = traceService;
        this.evidenceRecorder = evidenceRecorder;
        this.conversationService = conversationService;
        this.conversationSummaryService = conversationSummaryService;
        this.toolExecutor = toolExecutor;
    }

    public AgentAnswer chat(AgentExecutionRequest request) {
        return execute(request, AgentObserver.NOOP, false);
    }

    public AgentAnswer stream(AgentExecutionRequest request, AgentObserver observer) {
        return execute(request, observer == null ? AgentObserver.NOOP : observer, true);
    }

    private AgentAnswer execute(AgentExecutionRequest request, AgentObserver observer, boolean streamAnswer) {
        validateRequest(request);
        List<Long> knowledgeBaseIds = distinctPositive(request.knowledgeBaseIds(), "知识库编号");
        List<Long> documentIds = distinctPositive(request.documentIds(), "文档编号");
        if (!knowledgeBaseIds.isEmpty()) {
            // 提前校验知识库归属，避免 Agent 在无权限知识库上反复试错。
            knowledgeBaseService.getAccessible(knowledgeBaseIds, request.ownerId());
        }
        String conversationId = normalizeConversationId(request.conversationId(), request.ownerId(),
                request.message());
        String traceId = UUID.randomUUID().toString().replace("-", "");
        long startedAt = System.nanoTime();
        long deadline = startedAt + properties.executionTimeout().toNanos();
        RagTrace trace = traceService.start(traceId, request.ownerId(), request.message(),
                knowledgeBaseIds, "AGENT", conversationId);
        observer.onTrace(traceId);
        try {
            AgentAnswer answer = run(request, observer, streamAnswer, trace, traceId,
                    knowledgeBaseIds, documentIds, deadline, conversationId);
            traceService.finish(trace.getId(), true, elapsedMillis(startedAt), answer.answer(),
                    answer.usage().inputTokens(), answer.usage().outputTokens(), null);
            return answer;
        } catch (RuntimeException exception) {
            traceService.finish(trace.getId(), false, elapsedMillis(startedAt), null,
                    0, 0, exception.getMessage());
            throw exception;
        }
    }

    private AgentAnswer run(AgentExecutionRequest request, AgentObserver observer, boolean streamAnswer,
            RagTrace trace, String traceId, List<Long> knowledgeBaseIds, List<Long> documentIds,
            long deadline, String conversationId) {
        List<String> warnings = new ArrayList<>();
        SpanHandle memorySpan = traceService.startSpan(traceId, null, "MEMORY_CONTEXT",
                "conversation=" + conversationId);
        ConversationContext conversation;
        try {
            conversation = conversationSummaryService.load(conversationId, request.ownerId(),
                    request.history());
            traceService.finishSpan(memorySpan, "SUCCESS", "messages=" + conversation.messages().size(),
                    null, null, conversation.usage().inputTokens(), conversation.usage().outputTokens());
        } catch (RuntimeException exception) {
            traceService.failSpan(memorySpan, "MEMORY_ERROR", exception.getMessage());
            throw exception;
        }
        warnings.addAll(conversation.warnings());
        TaskContext taskContext = new TaskContext(request.message(), request.ownerId(), knowledgeBaseIds,
                documentIds, conversation.messages(), conversation.summary());
        SpanHandle taskSpan = traceService.startSpan(traceId, null, "TASK_UNDERSTANDING",
                "questionLength=" + request.message().length());
        TaskUnderstanding task;
        try {
            task = understandingService.understand(taskContext);
            traceService.finishSpan(taskSpan, "SUCCESS", "taskType=" + task.taskType()
                            + ", confidence=" + task.confidence(), null, null,
                    task.usage().inputTokens(), task.usage().outputTokens());
        } catch (RuntimeException exception) {
            traceService.failSpan(taskSpan, "TASK_UNDERSTANDING_ERROR", exception.getMessage());
            throw exception;
        }
        traceService.updateStrategy(trace.getId(), "AGENT:" + task.taskType());
        observer.onTask(task);

        warnings.addAll(task.warnings());
        List<AgentStep> steps = new ArrayList<>();
        EvidenceCollector evidence = new EvidenceCollector(properties.maxEvidence());
        ToolExecutionContext toolContext = new ToolExecutionContext(traceId, request.ownerId(),
                knowledgeBaseIds, documentIds, request.history(), evidence,
                request.topK() == null ? 5 : Math.min(Math.max(request.topK(), 1), 20));
        List<AgentMessage> messages = buildMessages(taskContext, task);
        boolean toolsEnabled = task.taskType() != com.teammind.ai.agent.task.TaskType.GENERAL_CHAT
                && task.taskType() != com.teammind.ai.agent.task.TaskType.UNKNOWN;
        List<AgentToolSpec> toolSpecs = toolsEnabled ? toolRegistry.specifications() : List.of();

        String finalContent = null;
        boolean modelUnavailable = false;
        TerminationReason termination = null;
        int toolCallCount = 0;
        int consecutiveFailures = 0;
        ModelUsage usage = conversation.usage().plus(task.usage());

        for (int iteration = 1; iteration <= properties.maxIterations(); iteration++) {
            if (deadlineExceeded(deadline)) {
                termination = TerminationReason.TIMEOUT;
                break;
            }
            AgentModelResponse response;
            SpanHandle decisionSpan = traceService.startSpan(traceId, null, "AGENT_DECISION",
                    "iteration=" + iteration + ", messages=" + messages.size());
            try {
                response = modelGateway.complete(new AgentModelRequest(
                        AgentPrompts.REACT_SYSTEM_PROMPT, messages, toolSpecs, false));
                traceService.finishSpan(decisionSpan, "SUCCESS",
                        "toolCalls=" + response.toolCalls().size(), null, null,
                        response.usage().inputTokens(), response.usage().outputTokens());
            } catch (RuntimeException exception) {
                traceService.failSpan(decisionSpan, "MODEL_ERROR", exception.getMessage());
                if (steps.isEmpty() && finalContent == null) {
                    // 第一次模型调用就失败，说明 Agent 无法启动，按依赖服务不可用返回。
                    throw exception;
                }
                warnings.add("模型调用失败，已使用已有观察生成降级回答：" + exception.getMessage());
                termination = TerminationReason.MODEL_ERROR;
                modelUnavailable = true;
                break;
            }
            usage = usage.plus(response.usage());
            if (!response.hasToolCalls()) {
                if (response.content() == null || response.content().isBlank()) {
                    termination = TerminationReason.MODEL_ERROR;
                    modelUnavailable = true;
                    break;
                }
                finalContent = response.content();
                termination = TerminationReason.COMPLETED;
                break;
            }

            if (response.content() != null && !response.content().isBlank()) {
                addStep(steps, observer, AgentStepType.THOUGHT, null, response.content(), true, 0);
            }
            messages.add(AgentMessage.assistantToolCalls(response.content(), response.toolCalls()));
            boolean stopForLimit = false;
            boolean stopForTimeout = false;
            for (AgentToolCall call : response.toolCalls()) {
                SpanHandle toolSpan = traceService.startSpan(traceId,
                        decisionSpan == null ? null : decisionSpan.spanId(), "TOOL_CALL",
                        call.name() + " " + abbreviate(call.arguments(), 300));
                if (toolCallCount >= properties.maxToolCalls()) {
                    // 未执行的调用也必须回填 tool 消息，保持 OpenAI 工具调用协议完整。
                    messages.add(AgentMessage.tool(call.id(), "未执行：已达到工具调用次数上限。"));
                    traceService.failSpan(toolSpan, "TOOL_LIMIT", "未执行：已达到工具调用次数上限。");
                    stopForLimit = true;
                    continue;
                }
                if (deadlineExceeded(deadline)) {
                    messages.add(AgentMessage.tool(call.id(), "未执行：本次执行已超时。"));
                    traceService.failSpan(toolSpan, "AGENT_TIMEOUT", "未执行：本次执行已超时。");
                    stopForTimeout = true;
                    stopForLimit = true;
                    continue;
                }
                toolCallCount++;
                if (call.name() == null || call.name().isBlank()) {
                    messages.add(AgentMessage.tool(call.id(), "未执行：工具名称不能为空。"));
                    traceService.failSpan(toolSpan, "INVALID_TOOL", "工具名称不能为空");
                    consecutiveFailures++;
                    continue;
                }
                if (call.arguments() != null && call.arguments().length() > MAX_TOOL_ARGUMENT_CHARS) {
                    ToolResult tooLarge = ToolResult.failure("工具参数过长，已拒绝执行。",
                            java.util.Map.of("error", "ARGUMENTS_TOO_LARGE"));
                    addStep(steps, observer, AgentStepType.ACTION, call.name(),
                            abbreviate(call.arguments(), STEP_CONTENT_CHARS), false, 0);
                    addStep(steps, observer, AgentStepType.OBSERVATION, call.name(), tooLarge.content(),
                            false, 0);
                    messages.add(AgentMessage.tool(call.id(), tooLarge.content()));
                    traceService.failSpan(toolSpan, "ARGUMENTS_TOO_LARGE", tooLarge.content());
                    consecutiveFailures++;
                    continue;
                }
                addStep(steps, observer, AgentStepType.ACTION, call.name(),
                        abbreviate(call.arguments(), STEP_CONTENT_CHARS), true, 0);
                ToolExecution execution = executeTool(call, toolContext, deadline);
                addStep(steps, observer, AgentStepType.OBSERVATION, call.name(),
                        abbreviate(execution.result().content(), STEP_CONTENT_CHARS),
                        execution.result().success(), execution.durationMillis());
                messages.add(AgentMessage.tool(call.id(), execution.result().content()));
                String errorType = execution.result().success() ? null
                        : String.valueOf(execution.result().metadata().getOrDefault("error", "TOOL_FAILED"));
                traceService.finishSpan(toolSpan,
                        execution.result().success() ? "SUCCESS" : "FAILED",
                        abbreviate(execution.result().content(), 1000), errorType,
                        execution.result().success() ? null : execution.result().content(), 0, 0);
                if (execution.result().success()) {
                    consecutiveFailures = 0;
                } else {
                    consecutiveFailures++;
                }
            }
            if (stopForLimit) {
                termination = stopForTimeout ? TerminationReason.TIMEOUT : TerminationReason.MAX_TOOL_CALLS;
                break;
            }
            if (consecutiveFailures >= properties.maxConsecutiveFailures()) {
                warnings.add("连续工具调用失败达到上限，已停止继续调用工具。");
                termination = TerminationReason.TOOL_FAILURE_LIMIT;
                break;
            }
        }
        if (termination == null) {
            termination = TerminationReason.MAX_ITERATIONS;
        }
        if (termination == TerminationReason.MAX_ITERATIONS) {
            warnings.add("达到最大推理轮数，已基于现有观察生成最终答案。");
        }

        SpanHandle finalSpan = traceService.startSpan(traceId, null, "FINAL_ANSWER",
                "termination=" + termination);
        FinalAnswer finalAnswer;
        if (finalContent != null) {
            if (streamAnswer) {
                finalAnswer = generateFinalAnswer(messages, evidence.size(), TerminationReason.COMPLETED,
                        observer, warnings);
            } else {
                finalAnswer = sanitize(finalContent, evidence.size(), warnings);
            }
        } else if (modelUnavailable) {
            finalAnswer = new FinalAnswer(fallbackAnswer(termination), Set.of(), ModelUsage.empty(), true);
        } else if (termination == TerminationReason.TIMEOUT && deadlineExceeded(deadline)) {
            // 已超时不再发起新的模型请求，直接使用已有观察生成确定性回答。
            warnings.add("执行已超时，已停止继续调用模型和工具。");
            finalAnswer = new FinalAnswer(fallbackAnswer(termination), Set.of(), ModelUsage.empty(), true);
        } else {
            finalAnswer = generateFinalAnswer(messages, evidence.size(), termination, observer, warnings);
        }
        usage = usage.plus(finalAnswer.usage());
        if (finalAnswer.answer() == null || finalAnswer.answer().isBlank()) {
            warnings.add("最终答案为空，已使用确定性降级回答。");
            finalAnswer = new FinalAnswer(fallbackAnswer(termination), Set.of(),
                    finalAnswer.usage(), true);
        }
        if (finalAnswer.degraded() && termination == TerminationReason.COMPLETED) {
            termination = TerminationReason.MODEL_ERROR;
        }
        traceService.finishSpan(finalSpan, finalAnswer.degraded() ? "FAILED" : "SUCCESS",
                "answerLength=" + finalAnswer.answer().length(),
                finalAnswer.degraded() ? "MODEL_FALLBACK" : null,
                finalAnswer.degraded() ? "最终答案使用降级回答" : null,
                finalAnswer.usage().inputTokens(), finalAnswer.usage().outputTokens());
        evidenceRecorder.markCitations(traceId, evidence.chunkIds(finalAnswer.citedIndexes()));
        saveConversation(conversationId, request, traceId, finalAnswer.answer(), warnings);
        warnings = new ArrayList<>(new LinkedHashSet<>(warnings));
        return new AgentAnswer(finalAnswer.answer(), evidence.citations(), traceId, task.taskType(), task,
                steps, termination, warnings, usage, conversationId);
    }

    private FinalAnswer generateFinalAnswer(List<AgentMessage> messages, int evidenceCount,
            TerminationReason reason, AgentObserver observer, List<String> warnings) {
        List<AgentMessage> finalMessages = new ArrayList<>(messages);
        finalMessages.add(AgentMessage.user(AgentPrompts.finalInstruction(reason, evidenceCount)));
        if (observer != AgentObserver.NOOP) {
            CitationStreamSanitizer sanitizer = new CitationStreamSanitizer(evidenceCount);
            try {
                AgentModelResponse response = modelGateway.stream(new AgentModelRequest(
                                AgentPrompts.REACT_SYSTEM_PROMPT, finalMessages, List.of(), false),
                        delta -> {
                            String safe = sanitizer.accept(delta);
                            if (!safe.isEmpty()) {
                                observer.onAnswerDelta(safe);
                            }
                        });
                String tail = sanitizer.flush();
                if (!tail.isEmpty()) {
                    observer.onAnswerDelta(tail);
                }
                if (sanitizer.removed() > 0) {
                    warnings.add("已移除模型返回的 " + sanitizer.removed() + " 个无效引用编号。");
                }
                return new FinalAnswer(sanitizer.output(), sanitizer.cited(), response.usage());
            } catch (RuntimeException exception) {
                String tail = sanitizer.flush();
                if (!tail.isEmpty()) {
                    observer.onAnswerDelta(tail);
                }
                String partial = sanitizer.output();
                if (partial != null && !partial.isBlank()) {
                    warnings.add("流式回答中断，已返回已生成的部分内容。");
                    if (sanitizer.removed() > 0) {
                        warnings.add("已移除模型返回的 " + sanitizer.removed() + " 个无效引用编号。");
                    }
                    return new FinalAnswer(partial, sanitizer.cited(), ModelUsage.empty(), true);
                }
                warnings.add("最终答案生成失败，已使用确定性降级回答：" + exception.getMessage());
                return new FinalAnswer(fallbackAnswer(reason), Set.of(), ModelUsage.empty(), true);
            }
        }
        try {
            AgentModelResponse response = modelGateway.complete(new AgentModelRequest(
                    AgentPrompts.REACT_SYSTEM_PROMPT, finalMessages, List.of(), false));
            if (response.content() == null || response.content().isBlank()) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型未返回有效回答");
            }
            return sanitize(response.content(), evidenceCount, warnings, response.usage());
        } catch (RuntimeException exception) {
            warnings.add("最终答案生成失败，已使用确定性降级回答：" + exception.getMessage());
            return new FinalAnswer(fallbackAnswer(reason), Set.of(), ModelUsage.empty(), true);
        }
    }

    private FinalAnswer sanitize(String answer, int evidenceCount, List<String> warnings) {
        return sanitize(answer, evidenceCount, warnings, ModelUsage.empty());
    }

    private FinalAnswer sanitize(String answer, int evidenceCount, List<String> warnings, ModelUsage usage) {
        CitationStreamSanitizer sanitizer = new CitationStreamSanitizer(evidenceCount);
        sanitizer.accept(answer);
        sanitizer.flush();
        if (sanitizer.removed() > 0) {
            warnings.add("已移除模型返回的 " + sanitizer.removed() + " 个无效引用编号。");
        }
        return new FinalAnswer(sanitizer.output(), sanitizer.cited(), usage);
    }

    private List<AgentMessage> buildMessages(TaskContext context, TaskUnderstanding task) {
        List<AgentMessage> messages = new ArrayList<>();
        List<ConversationMessage> history = context.history();
        int start = Math.max(0, history.size() - properties.maxHistoryMessages());
        for (ConversationMessage message : history.subList(start, history.size())) {
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            String content = abbreviate(message.content(), 2000);
            if ("assistant".equals(message.role())) {
                messages.add(AgentMessage.assistant(content));
            } else if ("user".equals(message.role())) {
                messages.add(AgentMessage.user(content));
            }
            // 其他角色（包括客户端伪造的 system）一律丢弃，防止提示词注入。
        }
        messages.add(AgentMessage.user(AgentPrompts.buildAgentUserContent(context, task)));
        return messages;
    }

    private ToolExecution executeTool(AgentToolCall call, ToolExecutionContext context, long deadline) {
        long startedAt = System.nanoTime();
        long remaining = deadline - startedAt;
        if (remaining <= 0) {
            return new ToolExecution(ToolResult.failure("工具未执行：本次执行已超时。",
                    java.util.Map.of("error", "AGENT_TIMEOUT")), 0);
        }
        long timeoutNanos = Math.min(properties.toolTimeout().toNanos(), remaining);
        Future<ToolResult> future;
        try {
            future = toolExecutor.submit(() ->
                    toolRegistry.execute(call.name(), call.arguments(), context));
        } catch (RuntimeException exception) {
            log.warn("Agent 工具执行器不可用：name={}", call.name(), exception);
            return new ToolExecution(ToolResult.failure("工具执行器暂不可用，请稍后重试。",
                    java.util.Map.of("error", "EXECUTOR_UNAVAILABLE")), elapsedMillis(startedAt));
        }
        try {
            ToolResult result = future.get(Math.max(1, TimeUnit.NANOSECONDS.toMillis(timeoutNanos)),
                    TimeUnit.MILLISECONDS);
            return new ToolExecution(result, elapsedMillis(startedAt));
        } catch (TimeoutException exception) {
            future.cancel(true);
            log.warn("Agent 工具调用超时：name={}", call.name());
            return new ToolExecution(ToolResult.failure("工具执行超时，请调整问题或稍后重试。",
                    java.util.Map.of("error", "TOOL_TIMEOUT")), elapsedMillis(startedAt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ToolExecution(ToolResult.failure("工具执行被中断。",
                    java.util.Map.of("error", "TOOL_INTERRUPTED")), elapsedMillis(startedAt));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            log.warn("Agent 工具执行异常：name={}", call.name(), cause);
            String message = cause instanceof BusinessException businessException
                    ? businessException.getMessage() : "工具执行失败，请调整参数或稍后重试。";
            return new ToolExecution(ToolResult.failure(message,
                    java.util.Map.of("error", "TOOL_FAILED")), elapsedMillis(startedAt));
        }
    }

    private void addStep(List<AgentStep> steps, AgentObserver observer, AgentStepType type,
            String name, String content, boolean success, long durationMillis) {
        AgentStep step = new AgentStep(steps.size() + 1, type, name,
                abbreviate(content, STEP_CONTENT_CHARS), java.util.Map.of(), success, durationMillis);
        steps.add(step);
        observer.onStep(step);
    }

    private String normalizeConversationId(String conversationId, Long ownerId, String message) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        String normalized = conversationId.trim();
        // 先创建/校验会话元数据，保证 ai_trace 的外键和后续 Redis 写入都指向同一会话。
        conversationService.ensure(normalized, ownerId, titleFromMessage(message));
        return normalized;
    }

    private void saveConversation(String conversationId, AgentExecutionRequest request, String traceId,
            String answer, List<String> warnings) {
        if (conversationId == null || answer == null || answer.isBlank()) {
            return;
        }
        String now = java.time.LocalDateTime.now().toString();
        try {
            conversationService.appendMessages(conversationId, request.ownerId(), List.of(
                    new StoredMessage("user", request.message(), traceId, now),
                    new StoredMessage("assistant", answer, traceId, now)));
        } catch (RuntimeException exception) {
            // 会话保存失败不能回滚已生成的回答，只记录警告供客户端展示。
            log.warn("会话消息保存失败：conversationId={}", conversationId, exception);
            warnings.add("会话记忆保存失败，本次回答未写入短期上下文。");
        }
    }

    private String titleFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return "新会话";
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60);
    }

    private String fallbackAnswer(TerminationReason reason) {
        String detail = switch (reason) {
            case MAX_ITERATIONS -> "已达到最大推理轮数";
            case MAX_TOOL_CALLS -> "已达到工具调用次数上限";
            case TIMEOUT -> "本次执行超时";
            case TOOL_FAILURE_LIMIT -> "连续工具调用失败";
            case MODEL_ERROR -> "模型服务暂时不可用";
            case UNKNOWN -> "任务无法识别";
            case COMPLETED -> "未能生成有效答案";
        };
        return "本次请求未能生成完整答案（" + detail + "）。请缩小问题范围、补充明确信息后重试。";
    }

    private void validateRequest(AgentExecutionRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求不能为空");
        }
        if (request.message() == null || request.message().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "问题不能为空");
        }
        if (request.ownerId() == null || request.ownerId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "归属用户编号必须为正整数");
        }
    }

    private List<Long> distinctPositive(List<Long> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + "必须为正整数");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private boolean deadlineExceeded(long deadline) {
        return System.nanoTime() >= deadline;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private record ToolExecution(ToolResult result, long durationMillis) {
    }

    private record FinalAnswer(String answer, Set<Integer> citedIndexes, ModelUsage usage,
            boolean degraded) {
        private FinalAnswer(String answer, Set<Integer> citedIndexes, ModelUsage usage) {
            this(answer, citedIndexes, usage, false);
        }

        private FinalAnswer {
            citedIndexes = citedIndexes == null ? Set.of() : Set.copyOf(citedIndexes);
            usage = usage == null ? ModelUsage.empty() : usage;
        }
    }
}
