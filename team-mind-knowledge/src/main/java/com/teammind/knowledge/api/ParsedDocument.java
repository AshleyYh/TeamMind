package com.teammind.knowledge.api;

import java.util.List;

/** 解析后的统一文本结构，PDF 保留页码，其他格式页码为空。 */
public record ParsedDocument(List<PageContent> pages) {
    public record PageContent(Integer pageNumber, String content) {
    }
}
