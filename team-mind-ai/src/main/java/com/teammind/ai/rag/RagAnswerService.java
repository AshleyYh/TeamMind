package com.teammind.ai.rag;

import com.teammind.ai.model.ChatCompletionGateway;
import com.teammind.ai.prompt.RagPrompts;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.application.KnowledgeSearchService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagAnswerService {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final KnowledgeSearchService searchService;
    private final ChatCompletionGateway chatGateway;

    public RagAnswerService(KnowledgeSearchService searchService, ChatCompletionGateway chatGateway) {
        this.searchService = searchService;
        this.chatGateway = chatGateway;
    }

    public RagAnswer answer(String query, List<Long> knowledgeBaseIds, Long ownerId, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "问题不能为空");
        }
        List<RetrievedChunk> chunks = searchService.search(query, knowledgeBaseIds, ownerId, topK);
        if (chunks.isEmpty()) {
            return new RagAnswer("当前知识库没有找到足够依据回答该问题。", List.of());
        }
        // 引用编号由服务端固定生成，不接受模型传入的文档 ID 或任意引用位置。
        String answer = chatGateway.generate(RagPrompts.SYSTEM_PROMPT,
                RagPrompts.buildUserContent(query, chunks));
        validateCitationIndexes(answer, chunks.size());
        List<Citation> citations = java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> toCitation(index + 1, chunks.get(index))).toList();
        return new RagAnswer(answer, citations);
    }

    private void validateCitationIndexes(String answer, int evidenceCount) {
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index < 1 || index > evidenceCount) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                        "模型返回了不存在的证据引用，已拒绝本次回答");
            }
        }
    }

    private Citation toCitation(int index, RetrievedChunk chunk) {
        return new Citation(index, chunk.chunkId(), chunk.documentId(), chunk.knowledgeBaseId(),
                chunk.documentName(), chunk.documentVersion(), chunk.pageNumber(), chunk.content(), chunk.score());
    }

    public record RagAnswer(String answer, List<Citation> citations) {
    }

    public record Citation(int index, Long chunkId, Long documentId, Long knowledgeBaseId,
            String documentName, Integer documentVersion, Integer pageNumber, String content, double score) {
    }
}
