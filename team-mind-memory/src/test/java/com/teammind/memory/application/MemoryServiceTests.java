package com.teammind.memory.application;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.memory.config.MemoryProperties;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryStatus;
import com.teammind.memory.domain.MemoryType;
import com.teammind.memory.mapper.MemoryMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryServiceTests {
    private final MemoryMapper memoryMapper = mock(MemoryMapper.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final MemoryProperties properties = new MemoryProperties(Duration.ofDays(7), 200, 20, 40,
            2000, 5, 5);
    private final MemoryService service = new MemoryService(memoryMapper, conversationService, properties);

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito 单测没有 MyBatis 启动流程，需要手动注册实体元信息才能解析 Lambda 列名。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Memory.class);
    }

    @Test
    void 新记忆保存为草稿并补全标题摘要() {
        when(memoryMapper.selectOne(any())).thenReturn(null);
        when(memoryMapper.insert(any(Memory.class))).thenAnswer(invocation -> {
            Memory memory = invocation.getArgument(0);
            memory.setId(10L);
            return 1;
        });

        Memory memory = service.createDraft(1L, MemoryType.FACT, null,
                "订单模块使用 HikariCP，最大连接数为 30", null, "conv_1", "trace123", 0.9);

        assertThat(memory.getId()).isEqualTo(10L);
        assertThat(memory.getStatus()).isEqualTo(MemoryStatus.DRAFT);
        assertThat(memory.getVersion()).isEqualTo(1);
        assertThat(memory.getTitle()).contains("订单模块");
        assertThat(memory.getSummary()).contains("最大连接数");
        assertThat(memory.getSourceConversationId()).isEqualTo("conv_1");
        verify(conversationService).get("conv_1", 1L);
    }

    @Test
    void 重复内容返回已有记忆且不重复插入() {
        Memory existing = memory(10L, MemoryStatus.DRAFT, MemoryType.FACT);
        when(memoryMapper.selectOne(any())).thenReturn(existing);

        Memory result = service.createDraft(1L, MemoryType.FACT, null,
                existing.getContent(), null, null, null, 0.8);

        assertThat(result).isSameAs(existing);
        verify(memoryMapper, never()).insert(any(Memory.class));
    }

    @Test
    void 确认后记忆变为ACTIVE() {
        when(memoryMapper.selectById(10L)).thenReturn(memory(10L, MemoryStatus.DRAFT, MemoryType.FACT));

        Memory result = service.confirm(10L, 1L);

        assertThat(result.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        verify(memoryMapper).updateById(result);
    }

    @Test
    void 失效记忆不能确认或修改() {
        when(memoryMapper.selectById(10L)).thenReturn(memory(10L, MemoryStatus.INVALID, MemoryType.FACT));

        assertThatThrownBy(() -> service.confirm(10L, 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(10L, 1L, MemoryType.FACT, null, "新内容", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 修改内容递增版本() {
        Memory existing = memory(10L, MemoryStatus.ACTIVE, MemoryType.FACT);
        existing.setVersion(2);
        when(memoryMapper.selectById(10L)).thenReturn(existing);

        Memory result = service.update(10L, 1L, MemoryType.DECISION, "新标题", "新内容", "新摘要");

        assertThat(result.getVersion()).isEqualTo(3);
        assertThat(result.getMemoryType()).isEqualTo(MemoryType.DECISION);
        assertThat(result.getContent()).isEqualTo("新内容");
        verify(memoryMapper).updateById(result);
    }

    @Test
    void 删除记忆标记失效并逻辑删除() {
        when(memoryMapper.selectById(10L)).thenReturn(memory(10L, MemoryStatus.ACTIVE, MemoryType.FACT));

        service.delete(10L, 1L);

        ArgumentCaptor<Memory> captor = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MemoryStatus.INVALID);
        verify(memoryMapper).deleteById(10L);
    }

    @Test
    void 检索只查询ACTIVE记忆并更新使用时间() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(10L, MemoryStatus.ACTIVE,
                MemoryType.FACT)));

        List<Memory> result = service.search(1L, "订单 连接数", 5);

        assertThat(result).hasSize(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Memory>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(memoryMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("user_id", "status", "content");
        verify(memoryMapper).update(isNull(), any());
    }

    @Test
    void 非法来源会话和置信度被拒绝() {
        when(conversationService.get("conv_x", 1L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        assertThatThrownBy(() -> service.createDraft(1L, MemoryType.FACT, null, "内容", null,
                "conv_x", null, 0.9)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.createDraft(1L, MemoryType.FACT, null, "内容2", null,
                null, null, 1.5)).isInstanceOf(BusinessException.class);
    }

    private Memory memory(Long id, MemoryStatus status, MemoryType type) {
        Memory memory = new Memory();
        memory.setId(id);
        memory.setUserId(1L);
        memory.setMemoryType(type);
        memory.setTitle("订单连接数");
        memory.setContent("订单模块最大连接数为30");
        memory.setSummary("订单连接数为30");
        memory.setStatus(status);
        memory.setVersion(1);
        memory.setConfidence(0.9);
        memory.setDeleted(false);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }
}
