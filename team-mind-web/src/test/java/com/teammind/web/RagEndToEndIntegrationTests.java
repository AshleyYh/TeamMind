package com.teammind.web;

import com.teammind.ai.rag.RagAnswerService;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.application.DocumentService;
import com.teammind.knowledge.application.KnowledgeBaseService;
import com.teammind.knowledge.application.KnowledgeSearchService;
import com.teammind.knowledge.domain.Document;
import com.teammind.knowledge.domain.KnowledgeBase;
import com.teammind.knowledge.domain.ProcessingStatus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2 真实链路验收：真实 PostgreSQL、Qdrant 与 PDF，仅替换外部模型。 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
@Import(Phase2FakeModels.class)
@TestPropertySource(properties = {
        "teammind.knowledge.vector.collection=team_mind_phase2_it",
        "teammind.knowledge.vector.dimension=4",
        "teammind.knowledge.vector.score-threshold=0.0",
        "teammind.ai.qwen.embedding-dimension=4",
        "teammind.document.chunk-tokens=64",
        "teammind.document.overlap-tokens=10",
        "teammind.document.storage-path=target/phase2-it-documents"
})
class RagEndToEndIntegrationTests {

    private static final long OWNER_ID = 990_001L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private KnowledgeSearchService searchService;

    @Autowired
    private RagAnswerService ragAnswerService;

    @Test
    void PDF可以完成上传解析分块向量检索并返回真实引用() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, nickname, status, deleted, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """, OWNER_ID, "phase2-it-user", "not-a-real-password", "集成测试用户");

        KnowledgeBase knowledgeBase = knowledgeBaseService.create(
                "Phase 2 集成测试知识库", "用于验证基础 RAG 全链路", OWNER_ID);
        byte[] pdf = createPdf("Order module maximum pool size is 30.");
        MockMultipartFile file = new MockMultipartFile(
                "file", "order-design.pdf", "application/pdf", pdf);

        Document uploaded = documentService.upload(knowledgeBase.getId(), OWNER_ID, file);
        Document completed = awaitProcessing(uploaded.getId());
        assertThat(completed.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED.name());
        assertThat(completed.getCurrentVersion()).isEqualTo(1);

        List<RetrievedChunk> chunks = searchService.search(
                "What is the maximum pool size?", List.of(knowledgeBase.getId()), OWNER_ID, 3);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getFirst().content()).contains("maximum pool size is 30");
        assertThat(chunks.getFirst().pageNumber()).isEqualTo(1);

        RagAnswerService.RagAnswer answer = ragAnswerService.answer(
                "What is the maximum pool size?", List.of(knowledgeBase.getId()), OWNER_ID, 3);
        assertThat(answer.answer()).contains("[1]");
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations().getFirst().documentName()).isEqualTo("order-design.pdf");
        assertThat(answer.citations().getFirst().chunkId()).isEqualTo(chunks.getFirst().chunkId());

        // 删除后数据库分块和向量必须同时不可检索。
        documentService.delete(completed.getId(), OWNER_ID);
        assertThat(searchService.search("maximum pool size", List.of(knowledgeBase.getId()), OWNER_ID, 3))
                .isEmpty();
        knowledgeBaseService.delete(knowledgeBase.getId(), OWNER_ID);
    }

    private Document awaitProcessing(Long documentId) throws InterruptedException {
        Document latest = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            latest = documentService.get(documentId, OWNER_ID);
            if (!ProcessingStatus.PENDING.name().equals(latest.getProcessingStatus())
                    && !ProcessingStatus.PROCESSING.name().equals(latest.getProcessingStatus())) {
                return latest;
            }
            Thread.sleep(100);
        }
        return latest;
    }

    private byte[] createPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
