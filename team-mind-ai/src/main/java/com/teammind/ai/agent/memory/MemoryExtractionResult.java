package com.teammind.ai.agent.memory;

import com.teammind.memory.domain.Memory;

import java.util.List;

/** 记忆提取结果；warnings 记录被跳过的非法候选，不影响其他候选入库。 */
public record MemoryExtractionResult(List<Memory> memories, List<String> warnings) {

    public MemoryExtractionResult {
        memories = memories == null ? List.of() : List.copyOf(memories);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
