package com.teammind.knowledge.application;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.ParsedDocument;
import com.teammind.knowledge.config.DocumentProperties;
import com.teammind.knowledge.domain.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TextChunker {
    private final DocumentProperties properties;

    public TextChunker(DocumentProperties properties) {
        this.properties = properties;
    }

    public List<Chunk> chunk(Long documentId, Long knowledgeBaseId, int version, ParsedDocument document) {
        List<Chunk> result = new ArrayList<>();
        for (ParsedDocument.PageContent page : document.pages()) {
            splitPage(documentId, knowledgeBaseId, version, page, result);
            if (result.size() > properties.maxChunks()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文档分块数量超过当前上限");
            }
        }
        if (result.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文档中没有可用于向量化的文本");
        }
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setChunkIndex(i);
        }
        return result;
    }

    private void splitPage(Long documentId, Long knowledgeBaseId, int version,
            ParsedDocument.PageContent page, List<Chunk> result) {
        String text = page.content() == null ? "" : page.content().replace("\u0000", "").strip();
        if (text.isEmpty()) {
            return;
        }
        int step = properties.chunkTokens() - properties.overlapTokens();
        int offset = 0;
        // Token 仅为近似估算；保存原文片段，避免因为拼接空格破坏代码或改变引用内容。
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + estimateCharacterBudget(properties.chunkTokens()));
            Chunk chunk = new Chunk();
            chunk.setDocumentId(documentId);
            chunk.setKnowledgeBaseId(knowledgeBaseId);
            chunk.setDocumentVersion(version);
            chunk.setContent(text.substring(offset, end));
            chunk.setPageNumber(page.pageNumber());
            result.add(chunk);
            if (end == text.length()) {
                break;
            }
            offset = Math.min(text.length(), offset + estimateCharacterBudget(step));
        }
    }


    private int estimateCharacterBudget(int tokens) {
        // 中英文混合文本采用 1 Token 约 2 字符的保守估算，实际 Token 数由模型分词器决定。
        return Math.max(1, tokens * 2);
    }
}
