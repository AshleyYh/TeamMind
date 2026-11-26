package com.teammind.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teammind.observability.domain.TraceSpan;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TraceSpanMapper extends BaseMapper<TraceSpan> {
}
