package com.teammind.web;

import com.teammind.ai.model.ChatCompletionGateway;
import com.teammind.knowledge.api.EmbeddingGateway;
import com.teammind.knowledge.api.RerankerGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/** 集成测试专用模型替身：保留真实数据库和向量库，仅隔离外部付费模型。 */
@TestConfiguration(proxyBeanMethods = false)
public class Phase2FakeModels {

    @Bean
    @Primary
    EmbeddingGateway phase2TestEmbeddingGateway() {
        return new EmbeddingGateway() {
            @Override
            public List<List<Float>> embedDocuments(List<String> texts) {
                return texts.stream().map(text -> List.of(1.0F, 0.0F, 0.0F, 0.0F)).toList();
            }

            @Override
            public List<Float> embedQuery(String text) {
                return List.of(1.0F, 0.0F, 0.0F, 0.0F);
            }

            @Override
            public int dimension() {
                return 4;
            }
        };
    }

    @Bean
    @Primary
    RerankerGateway phase3TestRerankerGateway() {
        return (query, candidates) -> java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(index -> new RerankerGateway.RerankResult(index, 1.0 - index * 0.01)).toList();
    }

    @Bean
    @Primary
    ChatCompletionGateway phase2TestChatGateway() {
        return (systemPrompt, userContent) -> "订单模块的最大连接数为 30 [1]";
    }
}
