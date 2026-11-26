package com.teammind.memory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.memory.config.MemoryProperties;
import com.teammind.memory.domain.Conversation;
import com.teammind.memory.domain.StoredMessage;
import com.teammind.memory.mapper.ConversationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTests {
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryProperties properties = new MemoryProperties(Duration.ofDays(7), 10, 4, 6,
            1000, 5, 3);
    private final ConversationService service = new ConversationService(conversationMapper,
            redisTemplate, objectMapper, properties);

    {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Conversation.class);
    }

    @Test
    void 创建会话保存归属和标题() {
        when(conversationMapper.selectOne(any())).thenReturn(null);

        service.ensure("conv_1", 1L, "订单模块最大连接数是多少？");

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getConversationId()).isEqualTo("conv_1");
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getTitle()).contains("订单模块");
        assertThat(captor.getValue().getDeleted()).isFalse();
    }

    @Test
    void 会话归属不一致时不暴露存在性() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation(1L, 2L));

        assertThatThrownBy(() -> service.get("conv_1", 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 追加消息写入Redis并按上限裁剪() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation(1L, 1L));

        service.appendMessages("conv_1", 1L, List.of(
                new StoredMessage("user", "你好", null, null),
                new StoredMessage("assistant", "你好，我是 TeamMind", "trace123", null)));

        String key = "teammind:memory:conversation:conv_1:messages";
        verify(listOperations).rightPushAll(eq(key), anyList());
        verify(listOperations).trim(key, -10L, -1L);
        verify(redisTemplate).expire(key, Duration.ofDays(7));
        verify(conversationMapper).updateById(any(Conversation.class));
    }

    @Test
    void 读取消息时跳过损坏JSON() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation(1L, 1L));
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(List.of(
                "{\"role\":\"user\",\"content\":\"你好\",\"traceId\":null,\"createdAt\":\"2026-01-01T00:00:00\"}",
                "不是JSON"));

        List<StoredMessage> messages = service.loadMessages("conv_1", 1L, 5);

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.role()).isEqualTo("user");
            assertThat(message.content()).isEqualTo("你好");
        });
    }

    @Test
    void Redis不可用时返回会话记忆不可用() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation(1L, 1L));
        when(listOperations.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> service.loadMessages("conv_1", 1L, 5))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void 删除会话逻辑删除并清理Redis() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation(1L, 1L));

        service.delete("conv_1", 1L);

        verify(conversationMapper).deleteById(1L);
        verify(redisTemplate).delete(anyCollection());
    }

    @Test
    void 摘要读写使用独立Key() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation(1L, 1L));
        when(valueOperations.get("teammind:memory:conversation:conv_1:summary"))
                .thenReturn("{\"summary\":\"历史摘要\",\"summarizedMessages\":2,"
                        + "\"updatedAt\":\"2026-01-01T00:00:00\"}");

        var summary = service.getSummary("conv_1", 1L);

        assertThat(summary.summary()).isEqualTo("历史摘要");
        assertThat(summary.summarizedMessages()).isEqualTo(2);
    }

    @Test
    void 非法会话标识被拒绝() {
        assertThatThrownBy(() -> service.ensure("bad id!", 1L, "标题"))
                .isInstanceOf(BusinessException.class);
        verify(conversationMapper, never()).insert(any(Conversation.class));
    }

    private Conversation conversation(Long id, Long userId) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setConversationId("conv_1");
        conversation.setUserId(userId);
        conversation.setTitle("测试会话");
        conversation.setDeleted(false);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }
}
