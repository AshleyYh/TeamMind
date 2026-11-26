package com.teammind.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teammind.memory.domain.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
