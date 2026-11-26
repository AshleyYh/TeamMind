package com.teammind.ai.agent.model;

import java.util.function.Consumer;

/**
 * Agent 使用的对话模型端口。任务理解和 ReAct 循环都依赖此接口，测试时可以替换为确定性实现。
 */
public interface AgentModelGateway {

    /** 同步调用，支持 Function Calling 与 JSON 模式。 */
    AgentModelResponse complete(AgentModelRequest request);

    /** 流式调用，按增量回调文本；返回累计文本与用量。 */
    AgentModelResponse stream(AgentModelRequest request, Consumer<String> deltaConsumer);
}
