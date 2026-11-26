package com.teammind.ai.agent.task;

/**
 * 历史查询时间范围，使用 ISO 日期字符串（yyyy-MM-dd）。
 * Phase 4 只做识别和校验，实际按时间过滤长期记忆属于 Phase 5。
 */
public record TimeRange(String start, String end) {
}
