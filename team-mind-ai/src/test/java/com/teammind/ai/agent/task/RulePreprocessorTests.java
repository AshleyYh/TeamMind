package com.teammind.ai.agent.task;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RulePreprocessorTests {
    private final RulePreprocessor preprocessor = new RulePreprocessor(properties());

    @Test
    void 空输入和超长输入在规则层拒绝() {
        assertThatThrownBy(() -> preprocessor.preprocess("   ", List.of(1L)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");

        String tooLong = "问".repeat(4010);
        assertThatThrownBy(() -> preprocessor.preprocess(tooLong, List.of(1L)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("长度");
    }

    @Test
    void 控制字符被清洗且保留正常换行() {
        RulePreprocessor.RuleResult result = preprocessor.preprocess(
                "订单\u0000模块\n\n\n\n最大连接数", List.of(1L));

        assertThat(result.question()).isEqualTo("订单 模块\n\n最大连接数");
    }

    @Test
    void 固定意图识别问候和历史问题() {
        assertThat(preprocessor.preprocess("你好！", List.of(1L)).fixedTaskType())
                .isEqualTo(TaskType.GENERAL_CHAT);
        assertThat(preprocessor.preprocess("我们之前是怎么解决连接池问题的？", List.of(1L)).fixedTaskType())
                .isEqualTo(TaskType.HISTORY_QUERY);
        assertThat(preprocessor.preprocess("帮我查找哪些文档提到连接池", List.of(1L)).fixedTaskType())
                .isEqualTo(TaskType.DOCUMENT_SEARCH);
    }

    @Test
    void 显式知识库范围与允许范围取交集() {
        RulePreprocessor.RuleResult result = preprocessor.preprocess(
                "只在知识库 1 和 2 中查询连接池配置", List.of(1L, 2L, 3L));

        assertThat(result.knowledgeBaseIds()).containsExactly(1L, 2L);
        assertThat(result.fixedTaskType()).isNull();
    }

    @Test
    void 知识库编号和序数写法都能识别() {
        assertThat(preprocessor.preprocess("知识库编号: 2 的最大连接数", List.of(1L, 2L)).knowledgeBaseIds())
                .containsExactly(2L);
        assertThat(preprocessor.preprocess("第 1 个知识库里的订单设计", List.of(1L, 2L)).knowledgeBaseIds())
                .containsExactly(1L);
    }

    @Test
    void 越界知识库被拒绝或忽略() {
        assertThatThrownBy(() -> preprocessor.preprocess("只在知识库 9 中查询", List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        RulePreprocessor.RuleResult partial = preprocessor.preprocess(
                "只在知识库 1 和 9 中查询", List.of(1L, 2L));
        assertThat(partial.knowledgeBaseIds()).containsExactly(1L);
        assertThat(partial.warnings()).anyMatch(warning -> warning.contains("超出访问范围"));
    }

    @Test
    void 没有显式范围时使用请求允许范围() {
        assertThat(preprocessor.preprocess("连接池大小是多少？", List.of(1L, 2L)).knowledgeBaseIds())
                .containsExactly(1L, 2L);
    }

    private AgentProperties properties() {
        return new AgentProperties(5, 8, 3, 12, 20, 4000, 6000, 1200,
                Duration.ofSeconds(90), Duration.ofSeconds(20));
    }
}
