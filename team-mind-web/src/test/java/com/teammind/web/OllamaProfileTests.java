package com.teammind.web;

import com.teammind.ai.config.QwenProperties;
import com.teammind.knowledge.config.QdrantKnowledgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 ollama profile 能与 test 上下文共用，并覆盖本地模型、维度和集合配置。 */
@SpringBootTest
@ActiveProfiles({"test", "ollama"})
class OllamaProfileTests {

    @Autowired
    private QwenProperties qwenProperties;

    @Autowired
    private QdrantKnowledgeProperties vectorProperties;

    @Test
    void 本地模型配置与向量维度正确装配() {
        assertThat(qwenProperties.baseUrl()).isEqualTo("http://localhost:11434/v1");
        assertThat(qwenProperties.apiKey()).isEqualTo("ollama");
        assertThat(qwenProperties.embeddingModel()).isEqualTo("qwen3-embedding:0.6b");
        assertThat(qwenProperties.chatModel()).isEqualTo("qwen3.5:9b");
        assertThat(qwenProperties.embeddingDimension()).isEqualTo(1024);
        assertThat(vectorProperties.collection()).isEqualTo("team_mind_chunks_local_qwen3_1024");
        assertThat(vectorProperties.dimension()).isEqualTo(1024);
    }
}
