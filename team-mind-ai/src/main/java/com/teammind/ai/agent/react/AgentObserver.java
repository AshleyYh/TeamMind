package com.teammind.ai.agent.react;

import com.teammind.ai.agent.task.TaskUnderstanding;

/** Agent 执行过程观察者。SSE 控制器用它推送事件，普通调用使用空实现。 */
public interface AgentObserver {
    AgentObserver NOOP = new AgentObserver() {
    };

    default void onTrace(String traceId) {
    }

    default void onTask(TaskUnderstanding task) {
    }

    default void onStep(AgentStep step) {
    }

    default void onAnswerDelta(String delta) {
    }
}
