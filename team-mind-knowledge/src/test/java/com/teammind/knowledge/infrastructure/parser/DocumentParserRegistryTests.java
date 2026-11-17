package com.teammind.knowledge.infrastructure.parser;

import com.teammind.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserRegistryTests {

    private final DocumentParserRegistry registry = new DocumentParserRegistry(
            List.of(new PdfDocumentParser(), new TikaDocumentParser()));

    @Test
    void PDF使用保留页码的专用解析器() {
        assertThat(registry.get("pdf")).isInstanceOf(PdfDocumentParser.class);
        assertThat(registry.get("docx")).isInstanceOf(TikaDocumentParser.class);
        assertThat(registry.get("txt")).isInstanceOf(TikaDocumentParser.class);
        assertThat(registry.get("md")).isInstanceOf(TikaDocumentParser.class);
    }

    @Test
    void 不支持的扩展名返回业务错误() {
        assertThatThrownBy(() -> registry.get("exe"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂不支持");
    }
}
