package com.teammind.web.controller;

import com.teammind.knowledge.application.DocumentService;
import com.teammind.knowledge.domain.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService service;

    @Test
    void 文档详情不暴露服务器文件路径() throws Exception {
        Document document = new Document();
        document.setId(1L);
        document.setKnowledgeBaseId(2L);
        document.setDocumentName("设计文档.pdf");
        document.setFileType("pdf");
        document.setFilePath("/secret/internal/path.pdf");
        document.setCurrentVersion(1);
        document.setProcessingStatus("COMPLETED");
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        when(service.get(anyLong(), anyLong())).thenReturn(document);

        mockMvc.perform(get("/api/v1/documents/1").param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentName").value("设计文档.pdf"))
                .andExpect(jsonPath("$.data.filePath").doesNotExist());
    }

    @Test
    void 雪花编号以字符串返回避免前端精度丢失() throws Exception {
        Document document = new Document();
        document.setId(2125154842707329025L);
        document.setKnowledgeBaseId(2125154842707328001L);
        document.setDocumentName("大编号文档.txt");
        document.setFileType("txt");
        document.setCurrentVersion(1);
        document.setProcessingStatus("COMPLETED");
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        when(service.get(anyLong(), anyLong())).thenReturn(document);

        mockMvc.perform(get("/api/v1/documents/2125154842707329025").param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("2125154842707329025"))
                .andExpect(jsonPath("$.data.knowledgeBaseId").value("2125154842707328001"));
    }

    @Test
    void 非正数所有者编号返回参数错误() throws Exception {
        mockMvc.perform(get("/api/v1/documents/1").param("ownerId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }
}
