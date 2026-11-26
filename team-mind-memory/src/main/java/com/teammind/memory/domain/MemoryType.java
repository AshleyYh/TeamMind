package com.teammind.memory.domain;

/** 长期记忆分类。只保存稳定、可复用的信息，不保存完整聊天记录。 */
public enum MemoryType {
    /** 明确的事实，例如“订单模块最大连接数为 30”。 */
    FACT,
    /** 用户偏好，例如“回答尽量简洁”。 */
    PREFERENCE,
    /** 已确认的决策，例如“统一使用 HikariCP”。 */
    DECISION,
    /** 历史经验，例如“上次故障通过扩容连接池解决”。 */
    EXPERIENCE,
    /** 用户或团队画像信息。 */
    PROFILE,
    /** 其他经过确认的信息。 */
    OTHER
}
