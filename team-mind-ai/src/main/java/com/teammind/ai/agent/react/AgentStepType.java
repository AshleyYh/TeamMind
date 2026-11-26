package com.teammind.ai.agent.react;

/** Agent 执行步骤类型，用于响应和 SSE 展示 ReAct 过程。 */
public enum AgentStepType {
    /** 模型在调用工具前的思考文本。 */
    THOUGHT,
    /** 模型发起的一次工具调用。 */
    ACTION,
    /** 工具返回的观察结果。 */
    OBSERVATION,
    /** 最终答案生成阶段。 */
    FINAL_ANSWER
}
