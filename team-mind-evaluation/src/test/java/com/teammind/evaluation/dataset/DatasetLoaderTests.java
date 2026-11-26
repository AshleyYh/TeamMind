package com.teammind.evaluation.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetLoaderTests {
    private final DatasetLoader loader = new DatasetLoader(new ObjectMapper());

    @Test
    void 三类内置数据集可加载并带版本和Hash() {
        var task = loader.loadTask(null);
        var rag = loader.loadRag(null);
        var memory = loader.loadMemory(null);

        assertThat(task.dataset().caseCount()).isGreaterThan(0);
        assertThat(task.dataset().version()).isNotBlank();
        assertThat(task.hash()).hasSize(64);
        assertThat(rag.dataset().cases()).allSatisfy(item ->
                assertThat(item.relevantDocumentNames()).isNotEmpty());
        assertThat(memory.dataset().cases()).allSatisfy(item ->
                assertThat(item.expectedKeyword()).isNotBlank());
        assertThat(loader.list()).hasSize(3);
    }

    @Test
    void 非白名单数据集被拒绝() {
        assertThatThrownBy(() -> loader.loadTask("../secret"))
                .isInstanceOf(BusinessException.class);
    }
}
