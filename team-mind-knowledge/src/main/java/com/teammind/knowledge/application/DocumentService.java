package com.teammind.knowledge.application;

import java.nio.file.Path;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.VectorStoreGateway;
import com.teammind.knowledge.config.DocumentProperties;
import com.teammind.knowledge.domain.Document;
import com.teammind.knowledge.domain.ProcessingStatus;
import com.teammind.knowledge.infrastructure.storage.LocalDocumentStorage;
import com.teammind.knowledge.mapper.ChunkMapper;
import com.teammind.knowledge.mapper.DocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DocumentService {
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final LocalDocumentStorage storage;
    private final DocumentProcessor documentProcessor;
    private final VectorStoreGateway vectorStore;
    private final DocumentProperties properties;

    public DocumentService(DocumentMapper documentMapper, ChunkMapper chunkMapper,
            KnowledgeBaseService knowledgeBaseService, LocalDocumentStorage storage,
            DocumentProcessor documentProcessor, VectorStoreGateway vectorStore,
            DocumentProperties properties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.storage = storage;
        this.documentProcessor = documentProcessor;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    @Transactional
    public Document upload(Long knowledgeBaseId, Long ownerId, MultipartFile file) {
        knowledgeBaseService.get(knowledgeBaseId, ownerId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上传文件不能为空");
        }
        if (file.getSize() > properties.maxFileBytes()) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "文件大小超过限制");
        }
        String originalName = safeOriginalName(file);
        String extension = extensionOf(originalName);
        if (!LocalDocumentStorage.SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只支持 PDF、DOCX、TXT 和 Markdown 文件");
        }
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId).eq(Document::getDocumentName, originalName));
        if (document == null) {
            document = new Document();
            document.setKnowledgeBaseId(knowledgeBaseId);
            document.setCurrentVersion(1);
            document.setDeleted(false);
            document.setCreatedAt(LocalDateTime.now());
            document.setDocumentName(originalName);
            document.setFileType("pending");
            document.setFilePath("pending");
            documentMapper.insert(document);
        } else {
            if (ProcessingStatus.PROCESSING.name().equals(document.getProcessingStatus())) {
                throw new BusinessException("同名文档正在处理，请稍后再上传");
            }
            document.setCurrentVersion(document.getCurrentVersion() + 1);
        }
        document.setProcessingStatus(ProcessingStatus.PENDING.name());
        document.setFailureReason(null);
        document.setUpdatedAt(LocalDateTime.now());
        LocalDocumentStorage.StoredDocument stored = storage.store(file, document.getId(), document.getCurrentVersion());
        document.setDocumentName(stored.name());
        document.setFileType(stored.extension());        document.setFilePath(stored.path().toString());
        documentMapper.updateById(document);
        // 只有事务提交后才调度异步任务，避免异步线程看不到上传记录。
        Long documentId = document.getId();
        int version = document.getCurrentVersion();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                documentProcessor.process(documentId, version);
            }
        });
        return document;
    }

    public List<Document> list(Long knowledgeBaseId, Long ownerId) {
        knowledgeBaseService.get(knowledgeBaseId, ownerId);
        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId).orderByDesc(Document::getCreatedAt));
    }

    public Document get(Long documentId, Long ownerId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        knowledgeBaseService.get(document.getKnowledgeBaseId(), ownerId);
        return document;
    }

    public Document reprocess(Long documentId, Long ownerId) {
        Document document = get(documentId, ownerId);
        if (ProcessingStatus.PROCESSING.name().equals(document.getProcessingStatus())) {
            throw new BusinessException("文档正在处理，请勿重复提交");
        }
        document.setProcessingStatus(ProcessingStatus.PENDING.name());
        document.setFailureReason(null);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
        documentProcessor.process(documentId, document.getCurrentVersion());
        return document;
    }

    private String safeOriginalName(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件名不能为空");
        }
        try {
            // 兼容浏览器可能提交的 Windows 路径，只保留文件名本身，阻止路径穿越。
            String name = Path.of(originalFilename.replace('\\', '/')).getFileName().toString().trim();
            if (name.isEmpty() || name.length() > 255) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件名长度必须在 1 到 255 个字符之间");
            }
            return name;
        } catch (java.nio.file.InvalidPathException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件名包含非法字符", exception);
        }
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    @Transactional
    public void delete(Long documentId, Long ownerId) {
        Document document = get(documentId, ownerId);
        if (ProcessingStatus.PROCESSING.name().equals(document.getProcessingStatus())) {
            throw new BusinessException("文档正在处理，暂不能删除");
        }
        // 先删除向量，再清理分块和逻辑删除元数据，确保数据库与向量库不残留可检索数据。
        vectorStore.deleteByDocumentId(documentId);
        chunkMapper.delete(new LambdaQueryWrapper<com.teammind.knowledge.domain.Chunk>()
                .eq(com.teammind.knowledge.domain.Chunk::getDocumentId, documentId));
        // @TableLogic 字段不能通过 updateById 可靠写入，使用框架逻辑删除更新 deleted。
        documentMapper.deleteById(documentId);
    }
}
