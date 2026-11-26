package com.teammind.observability.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.teammind.common.exception.BusinessException;
import com.teammind.observability.domain.RagTrace;
import com.teammind.observability.domain.TraceSpan;
import com.teammind.observability.mapper.RagTraceMapper;
import com.teammind.observability.mapper.TraceSpanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagTraceServiceTests {
    private final RagTraceMapper traceMapper = mock(RagTraceMapper.class);
    private final TraceSpanMapper spanMapper = mock(TraceSpanMapper.class);
    private final RagTraceService service = new RagTraceService(traceMapper, spanMapper);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, RagTrace.class);
        TableInfoHelper.initTableInfo(assistant, TraceSpan.class);
    }

    @Test
    void Span开始和结束记录状态耗时与Token() {
        when(spanMapper.insert(any(TraceSpan.class))).thenAnswer(invocation -> {
            TraceSpan span = invocation.getArgument(0);
            span.setId(1L);
            return 1;
        });

        RagTraceService.SpanHandle handle = service.startSpan("0123456789abcdef0123456789abcdef",
                null, "TOOL_CALL", "knowledgeSearch");
        service.finishSpan(handle, "SUCCESS", "results=2", null, null, 3, 4);

        ArgumentCaptor<TraceSpan> captor = ArgumentCaptor.forClass(TraceSpan.class);
        verify(spanMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(captor.getValue().getDuration()).isGreaterThanOrEqualTo(0);
        assertThat(captor.getValue().getInputTokens()).isEqualTo(3);
        assertThat(captor.getValue().getOutputTokens()).isEqualTo(4);
        assertThat(captor.getValue().getOutputSummary()).isEqualTo("results=2");
    }

    @Test
    void Trace收尾写入总Token和错误() {
        service.finish(99L, true, 120, "answer", 10, 5, null);

        ArgumentCaptor<RagTrace> captor = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(captor.getValue().getTotalTokens()).isEqualTo(15);
        assertThat(captor.getValue().getPromptTokens()).isEqualTo(10);
        assertThat(captor.getValue().getCompletionTokens()).isEqualTo(5);
        assertThat(captor.getValue().getFinishedAt()).isNotNull();
    }

    @Test
    void 失败Trace记录错误信息() {
        service.finish(99L, false, 20, null, 0, 0, "模型超时");

        ArgumentCaptor<RagTrace> captor = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("模型超时");
    }

    @Test
    void 查询和Span都校验归属() {
        when(traceMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.get("0123456789abcdef0123456789abcdef", 1L))
                .isInstanceOf(BusinessException.class);

        RagTrace trace = trace();
        when(traceMapper.selectOne(any())).thenReturn(trace);
        TraceSpan span = span();
        when(spanMapper.selectList(any())).thenReturn(List.of(span));

        assertThat(service.spans(trace.getTraceId(), 1L)).containsExactly(span);
    }

    @Test
    void 列表和一次性Span写入可用() {
        RagTrace trace = trace();
        when(traceMapper.selectList(any())).thenReturn(List.of(trace));

        assertThat(service.list(1L, 20)).containsExactly(trace);
        service.recordSpan(trace.getTraceId(), null, "RAG_RETRIEVAL", "SUCCESS", 30,
                "query=连接池", "results=5", null, null, 0, 0);
        verify(spanMapper).insert(any(TraceSpan.class));
    }

    private RagTrace trace() {
        RagTrace trace = new RagTrace();
        trace.setId(99L);
        trace.setTraceId("0123456789abcdef0123456789abcdef");
        trace.setUserId(1L);
        trace.setStatus("SUCCESS");
        trace.setCreatedAt(LocalDateTime.now());
        return trace;
    }

    private TraceSpan span() {
        TraceSpan span = new TraceSpan();
        span.setId(1L);
        span.setTraceId("0123456789abcdef0123456789abcdef");
        span.setSpanId("span-1");
        span.setSpanType("TOOL_CALL");
        span.setStatus("SUCCESS");
        return span;
    }
}
