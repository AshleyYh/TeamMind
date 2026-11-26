package com.teammind.memory.domain;

/** 长期记忆状态：候选草稿、已确认可用、已失效。 */
public enum MemoryStatus {
    DRAFT,
    ACTIVE,
    INVALID
}
