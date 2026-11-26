package com.teammind.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teammind.memory.domain.Memory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryMapper extends BaseMapper<Memory> {
}
