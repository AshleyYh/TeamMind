package com.teammind.web.controller;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.ai.agent.model.ConversationMessage;
import com.teammind.ai.agent.react.AgentAnswer;
import com.teammind.ai.agent.react.AgentExecutionRequest;
import com.teammind.ai.agent.react.AgentObserver;
import com.teammind.ai.agent.react.AgentStep;
import com.teammind.ai.agent.react.ReActAgentService;
import com.teammind.ai.agent.task.TaskUnderstanding;
import com.teammind.common.api.ApiResponse;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 对话接口：非流式返回完整执行步骤，SSE 接口按事件推送任务理解、工具调用、观察和答案增量。
 */
@Tag(name = "Agent 对话", description = "规则预处理 + 结构化任务理解 + ReAct 工具调用，支持非流式与 SSE 流式回答。")
@RestController
@RequestMapping("/api/v1")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ReActAgentService agentService;
    private final AgentProperties properties;
    private final ExecutorService streamExecutor;

    public AgentController(ReActAgentService agentService, AgentProperties properties,
            @Qualifier("agentStreamExecutor") ExecutorService streamExecutor) {
        this.agentService = agentService;
        this.properties = properties;
        this.streamExecutor = streamExecutor;
    }

    @Operation(summary = "Agent 对话（非流式）", description = """
            先做规则预处理和 LLM 结构化任务理解，再进入 ReAct 循环选择工具。
            响应包含 taskType、taskUnderstanding、steps、citations、termination、warnings 和 traceId。
            最大推理轮数、工具调用次数、整体超时和连续失败次数均由服务端限制；
            达到限制时会生成最终答案并在 termination 中说明原因。
            模型返回的越界引用编号会被移除，并在 warnings 中记录。
            """)
    @PostMapping("/agent/chat")
    public ApiResponse<AgentAnswer> chat(@Valid @RequestBody AgentChatRequest request) {
        return ApiResponse.success(agentService.chat(toExecutionRequest(request)));
    }

    @Operation(summary = "Agent 对话（SSE 流式）", description = """
            以 text/event-stream 返回事件：
            start（traceId）、task（任务理解）、step（THOUGHT/ACTION/OBSERVATION）、
            answer_delta（最终答案增量）、done（完整 AgentAnswer）、error（错误码与消息）。
            流式模式下无法撤回已发送文本，因此越界引用编号会被即时移除，不会中断回答。
            """)
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AgentChatRequest request) {
        // SSE 超时略大于 Agent 整体执行超时，避免服务端仍在生成时连接先被容器关闭。
        SseEmitter emitter = new SseEmitter(properties.executionTimeout().plusSeconds(120).toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> traceId = new java.util.concurrent.atomic.AtomicReference<>();
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(exception -> closed.set(true));
        AgentExecutionRequest executionRequest = toExecutionRequest(request);
        streamExecutor.execute(() -> {
            try {
                AgentAnswer answer = agentService.stream(executionRequest, new AgentObserver() {
                    @Override
                    public void onTrace(String value) {
                        traceId.set(value);
                        send(emitter, closed, "start", Map.of("traceId", value));
                    }

                    @Override
                    public void onTask(TaskUnderstanding task) {
                        send(emitter, closed, "task", task);
                    }

                    @Override
                    public void onStep(AgentStep step) {
                        send(emitter, closed, "step", step);
                    }

                    @Override
                    public void onAnswerDelta(String delta) {
                        send(emitter, closed, "answer_delta", Map.of("delta", delta));
                    }
                });
                if (!closed.get()) {
                    send(emitter, closed, "done", answer);
                }
                emitter.complete();
            } catch (BusinessException exception) {
                if (!closed.get()) {
                    send(emitter, closed, "error", new StreamError(
                            exception.getErrorCode().getCode(), exception.getMessage(), traceId.get()));
                }
                emitter.complete();
            } catch (RuntimeException exception) {
                log.error("Agent SSE 执行失败", exception);
                if (!closed.get()) {
                    send(emitter, closed, "error", new StreamError(
                            ErrorCode.INTERNAL_ERROR.getCode(), "服务器内部错误", traceId.get()));
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException exception) {
            // 客户端断开后继续推送没有意义，标记关闭并让调用方跳过后续事件。
            closed.set(true);
        }
    }

    private AgentExecutionRequest toExecutionRequest(AgentChatRequest request) {
        List<ConversationMessage> history = request.history() == null ? List.of()
                : request.history().stream()
                .map(message -> new ConversationMessage(message.role(), message.content())).toList();
        return new AgentExecutionRequest(request.message(), request.knowledgeBaseIds(), request.ownerId(),
                request.topK(), request.conversationId(), history, request.documentIds());
    }

    /** Agent 请求参数；知识库可选，普通聊天不需要检索。 */
    public record AgentChatRequest(
            @Schema(description = "用户问题", example = "订单模块的最大连接数是多少？", maxLength = 4000)
            @NotBlank @Size(max = 4000) String message,

            @Schema(description = "可访问的知识库 ID 列表，最多 20 个；普通聊天可以不传", example = "[1]")
            @Size(max = 20) List<@NotNull @Positive Long> knowledgeBaseIds,

            @Schema(description = "开发期归属用户 ID；当前尚未接入登录，不代表安全认证", example = "1")
            @NotNull @Positive Long ownerId,

            @Schema(description = "知识库检索返回的证据条数，默认 5", example = "5")
            @Positive @Max(20) Integer topK,

            @Schema(description = "会话标识；Phase 4 仅原样回传，不持久化会话", example = "conv_001")
            @Size(max = 64) String conversationId,

            @Schema(description = "本次请求携带的历史消息，最多 20 条；只能使用 user/assistant 角色")
            @Size(max = 20) List<@Valid HistoryMessage> history,

            @Schema(description = "用户指定的文档 ID 列表，最多 20 个", example = "[1001]")
            @Size(max = 20) List<@NotNull @Positive Long> documentIds) {
    }

    /** 客户端提供的会话历史；system 角色会被服务端丢弃。 */
    public record HistoryMessage(
            @Schema(description = "消息角色，只能是 user 或 assistant", example = "user")
            @NotBlank @Pattern(regexp = "user|assistant", message = "角色只能是 user 或 assistant")
            String role,

            @Schema(description = "消息内容", example = "之前提到的连接数是多少？", maxLength = 4000)
            @NotBlank @Size(max = 4000) String content) {
    }

    /** SSE 错误事件，HTTP 状态仍为 200，客户端按事件内容判断失败。 */
    public record StreamError(String code, String message, String traceId) {
    }
}
