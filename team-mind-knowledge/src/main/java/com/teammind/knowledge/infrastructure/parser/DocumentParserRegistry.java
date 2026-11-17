package com.teammind.knowledge.infrastructure.parser;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentParserRegistry {
    private final Map<String, DocumentParser> parsers = new HashMap<>();

    public DocumentParserRegistry(List<DocumentParser> documentParsers) {
        documentParsers.forEach(parser -> parser.supportedTypes().forEach(type -> {
            DocumentParser previous = parsers.put(type, parser);
            if (previous != null && !previous.getClass().equals(parser.getClass())) {
                throw new IllegalStateException("文档解析器重复注册: " + type);
            }
        }));
    }

    public DocumentParser get(String fileType) {
        DocumentParser parser = parsers.get(fileType);
        if (parser == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "暂不支持该文档类型");
        }
        return parser;
    }
}
