package com.teammind.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.teammind.knowledge.domain.KnowledgeBase;
import com.teammind.knowledge.mapper.DocumentMapper;
import com.teammind.knowledge.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceDeleteTests {

    @Test
    void 删除知识库使用逻辑删除更新deleted() {
        KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(11L);
        knowledgeBase.setName("研发知识库");
        knowledgeBase.setOwnerId(1L);
        knowledgeBase.setStatus(1);
        knowledgeBase.setDeleted(false);
        when(knowledgeBases.selectById(11L)).thenReturn(knowledgeBase);
        when(documents.selectCount(any(Wrapper.class))).thenReturn(0L);

        KnowledgeBaseService service = new KnowledgeBaseService(knowledgeBases, documents);
        service.delete(11L, 1L);

        verify(knowledgeBases).deleteById(11L);
        verify(knowledgeBases, never()).updateById(any(KnowledgeBase.class));
    }
}
