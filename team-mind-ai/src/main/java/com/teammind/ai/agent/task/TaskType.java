package com.teammind.ai.agent.task;

/** 第一版 Agent 任务类型，任务理解结果只能落在这些枚举内。 */
public enum TaskType {
    /** 知识库问答：需要检索知识库事实。 */
    KNOWLEDGE_QA,
    /** 历史经验查询：依赖 Phase 5 长期记忆。 */
    HISTORY_QUERY,
    /** 文档检索：定位或查找文档。 */
    DOCUMENT_SEARCH,
    /** 普通聊天：不依赖知识库。 */
    GENERAL_CHAT,
    /** 无法识别：需要向用户澄清。 */
    UNKNOWN
}
