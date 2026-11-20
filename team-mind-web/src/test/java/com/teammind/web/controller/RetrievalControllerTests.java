package com.teammind.web.controller;

import com.teammind.knowledge.api.SearchStrategy;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalControllerTests {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 未知检索策略无法反序列化为请求对象() {
        assertThatThrownBy(() -> mapper.readValue("""
                {"query":"连接池","knowledgeBaseIds":[1],"ownerId":1,"strategy":"UNKNOWN"}
                """, RetrievalController.SearchRequest.class));
    }

    @Test
    void 四种检索策略均可作为请求参数() throws Exception {
        for (SearchStrategy strategy : SearchStrategy.values()) {
            RetrievalController.SearchRequest request = mapper.readValue("""
                    {"query":"连接池","knowledgeBaseIds":[1],"ownerId":1,"strategy":"%s"}
                    """.formatted(strategy.name()), RetrievalController.SearchRequest.class);
            assertThat(request.strategy()).isEqualTo(strategy);
            assertThat(validator.validate(request)).isEmpty();
        }
    }

    @Test
    void 检索请求必须限定知识库和归属() {
        var request = new RetrievalController.SearchRequest("", List.of(), 0L, 0, null);
        assertThat(validator.validate(request)).hasSize(5);
    }
}
