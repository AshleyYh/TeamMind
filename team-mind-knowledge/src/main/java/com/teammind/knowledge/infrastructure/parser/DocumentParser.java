package com.teammind.knowledge.infrastructure.parser;

import com.teammind.knowledge.api.ParsedDocument;

import java.io.InputStream;
import java.util.Set;

public interface DocumentParser {
    Set<String> supportedTypes();
    ParsedDocument parse(InputStream inputStream) throws Exception;
}
