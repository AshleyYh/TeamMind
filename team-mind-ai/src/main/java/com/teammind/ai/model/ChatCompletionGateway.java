package com.teammind.ai.model;

/** 对话模型端口，RAG 业务只依赖此接口，便于替换具体模型供应商。 */
public interface ChatCompletionGateway {

    String generate(String systemPrompt, String userContent);

    /**
     * 带 Token 用量的生成方法。默认实现兼容只关心文本的模型/测试替身，
     * 具体供应商可以覆盖并返回真实 usage。
     */
    default ChatCompletionResult generateWithUsage(String systemPrompt, String userContent) {
        return new ChatCompletionResult(generate(systemPrompt, userContent), 0, 0);
    }

    record ChatCompletionResult(String content, int inputTokens, int outputTokens) {
    }
}
