package com.teammind.knowledge.api;

import java.util.List;

/** 向量化端口：知识模块只依赖接口，不依赖具体模型厂商。 */
public interface EmbeddingGateway {
    List<List<Float>> embedDocuments(List<String> texts);
    List<Float> embedQuery(String text);
    int dimension();
}
