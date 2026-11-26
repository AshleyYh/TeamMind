package com.teammind.ai.agent.react;

/** ReAct 循环的结束原因，用于告知模型、前端和日志，而不是静默截断。 */
public enum TerminationReason {
    /** 模型主动给出最终答案。 */
    COMPLETED,
    /** 达到最大模型轮数。 */
    MAX_ITERATIONS,
    /** 达到工具调用次数上限。 */
    MAX_TOOL_CALLS,
    /** 整体执行超时。 */
    TIMEOUT,
    /** 连续工具失败达到上限。 */
    TOOL_FAILURE_LIMIT,
    /** 模型调用失败，已使用已有观察降级。 */
    MODEL_ERROR,
    /** 任务无法识别。 */
    UNKNOWN
}
