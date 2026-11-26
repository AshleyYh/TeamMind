package com.teammind.ai.agent.tool;

import com.teammind.ai.api.Citation;
import com.teammind.knowledge.api.RetrievedChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单次 Agent 执行的证据编号器。
 *
 * <p>多个检索工具可能返回同一 Chunk，这里按 Chunk ID 去重并固定分配全局编号，
 * 保证最终答案中的 [n] 与返回的 citations 一一对应。</p>
 */
public class EvidenceCollector {
    private final int maxEvidence;
    private final List<RetrievedChunk> chunks = new ArrayList<>();
    private final Map<Long, Integer> indexByChunkId = new HashMap<>();

    public EvidenceCollector(int maxEvidence) {
        this.maxEvidence = Math.max(1, maxEvidence);
    }

    /** 返回证据编号；重复 Chunk 返回已有编号，超过上限返回 null。 */
    public synchronized Integer add(RetrievedChunk chunk) {
        if (chunk == null || chunk.chunkId() == null) {
            return null;
        }
        Integer existing = indexByChunkId.get(chunk.chunkId());
        if (existing != null) {
            return existing;
        }
        if (chunks.size() >= maxEvidence) {
            return null;
        }
        chunks.add(chunk);
        int index = chunks.size();
        indexByChunkId.put(chunk.chunkId(), index);
        return index;
    }

    public synchronized int size() {
        return chunks.size();
    }

    public synchronized List<Citation> citations() {
        List<Citation> citations = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            RetrievedChunk chunk = chunks.get(index);
            citations.add(new Citation(index + 1, chunk.chunkId(), chunk.documentId(), chunk.knowledgeBaseId(),
                    chunk.documentName(), chunk.documentVersion(), chunk.pageNumber(), chunk.content(), chunk.score(),
                    chunk.denseRank(), chunk.bm25Rank(), chunk.denseScore(), chunk.bm25Score(),
                    chunk.rrfScore(), chunk.rerankScore()));
        }
        return List.copyOf(citations);
    }

    public synchronized Set<Long> chunkIds(Set<Integer> indexes) {
        Set<Long> ids = new HashSet<>();
        for (Integer index : indexes) {
            if (index != null && index >= 1 && index <= chunks.size()) {
                ids.add(chunks.get(index - 1).chunkId());
            }
        }
        return ids;
    }

    public synchronized String render(int index, RetrievedChunk chunk, int maxContentChars) {
        String page = chunk.pageNumber() == null ? "" : "，第 " + chunk.pageNumber() + " 页";
        String version = chunk.documentVersion() == null ? "" : "，版本 " + chunk.documentVersion();
        return "证据 [%d] 文档《%s》%s%s\n%s".formatted(index,
                chunk.documentName() == null ? "未知文档" : chunk.documentName(), version, page,
                abbreviate(chunk.content(), maxContentChars));
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
