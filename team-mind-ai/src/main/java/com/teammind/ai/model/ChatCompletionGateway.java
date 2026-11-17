package com.teammind.ai.model;

/** 对话模型端口，RAG 业务只依赖此接口，便于替换具体模型供应商。 */
public interface ChatCompletionGateway {

    String generate(String systemPrompt, String userContent);
}
