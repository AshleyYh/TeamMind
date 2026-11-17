package com.teammind.knowledge.infrastructure.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserTests {

    @Test
    void Pdf解析保留实际页码() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String text : new String[]{"first page", "second page"}) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(output);
            var result = new PdfDocumentParser().parse(new ByteArrayInputStream(output.toByteArray()));
            assertThat(result.pages()).hasSize(2);
            assertThat(result.pages().get(0).content()).contains("first page");
            assertThat(result.pages().get(1).pageNumber()).isEqualTo(2);
            assertThat(result.pages().get(1).content()).contains("second page");
        }
    }

    @Test
    void Tika解析纯文本() throws Exception {
        var result = new TikaDocumentParser().parse(new ByteArrayInputStream("研发文档内容".getBytes(StandardCharsets.UTF_8)));
        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().getFirst().content()).contains("研发文档内容");
    }
}
