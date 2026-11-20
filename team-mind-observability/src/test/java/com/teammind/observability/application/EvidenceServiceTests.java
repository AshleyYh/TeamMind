package com.teammind.observability.application;

import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.observability.domain.Evidence;
import com.teammind.observability.mapper.EvidenceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EvidenceServiceTests {
    private final EvidenceMapper mapper = mock(EvidenceMapper.class);
    private final com.teammind.observability.mapper.RagTraceMapper traceMapper =
            mock(com.teammind.observability.mapper.RagTraceMapper.class);
    private final EvidenceService service = new EvidenceService(mapper, traceMapper);

    static {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Evidence.class);
    }

    @Test
    void 证据保存原始内容和全部排名分数() {
        RetrievedChunk chunk = new RetrievedChunk(11L, 22L, 1L, "研发.pdf", 2, 0, 3, "最大连接数30",
                "vector", 2, 1, 0.8, 5.3, 0.03, 0.95);
        service.record("0123456789abcdef0123456789abcdef", 1L, "连接数", List.of(1L),
                SearchStrategy.HYBRID_RERANK, List.of(chunk));
        ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
        verify(mapper).insert(captor.capture());
        Evidence evidence = captor.getValue();
        assertThat(evidence.getRetrievalType()).isEqualTo("HYBRID");
        assertThat(evidence.getContent()).isEqualTo("最大连接数30");
        assertThat(evidence.getDenseRank()).isEqualTo(2);
        assertThat(evidence.getRerankScore()).isEqualTo(0.95);
        assertThat(evidence.getReranked()).isTrue();
        assertThat(evidence.getCited()).isFalse();
        verify(traceMapper).insert((com.teammind.observability.domain.RagTrace)
                org.mockito.ArgumentMatchers.argThat((com.teammind.observability.domain.RagTrace trace) ->
                        trace.getTraceId().equals("0123456789abcdef0123456789abcdef")
                                && trace.getQuery().equals("连接数")
                                && trace.getKnowledgeBaseIds().equals("1")));
    }

    @Test
    void 只标记实际出现的引用且按归属查询() {
        service.markCitations("trace", Set.of(11L));
        verify(mapper).update(any(), org.mockito.ArgumentMatchers.argThat(wrapper ->
                wrapper.getSqlSegment().contains("trace_id") && wrapper.getSqlSegment().contains("chunk_id")));
        service.list("trace", 1L);
        verify(mapper).selectList(org.mockito.ArgumentMatchers.argThat(wrapper ->
                wrapper.getSqlSegment().contains("owner_id")));
    }
}
