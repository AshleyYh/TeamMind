package com.teammind.knowledge.infrastructure.parser;

import com.teammind.knowledge.api.ParsedDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class PdfDocumentParser implements DocumentParser {
    @Override
    public Set<String> supportedTypes() {
        return Set.of("pdf");
    }

    @Override
    public ParsedDocument parse(InputStream inputStream) throws Exception {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            List<ParsedDocument.PageContent> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            // 按页提取文本，页码来自 PDF 实际页序，便于前端准确溯源。
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(new ParsedDocument.PageContent(page, stripper.getText(document)));
            }
            return new ParsedDocument(pages);
        }
    }
}
