package com.teammind.knowledge.infrastructure.parser;

import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.ParsedDocument;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Component
public class TikaDocumentParser implements DocumentParser {
    private final Tika tika = new Tika();

    @Override
    public Set<String> supportedTypes() {
        // PDF 使用专用解析器保留页码，避免与 Tika 重复注册同一扩展名。
        return Set.of("docx", "txt", "md");
    }

    @Override
    public ParsedDocument parse(InputStream inputStream) throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "document");
        String content = tika.parseToString(inputStream, metadata, 10_000_000);
        return new ParsedDocument(List.of(new ParsedDocument.PageContent(null, content)));
    }
}
