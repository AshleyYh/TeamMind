package com.teammind.knowledge.infrastructure.storage;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

@Component
public class LocalDocumentStorage {
    /** 当前阶段允许上传的文档扩展名，扩展名统一使用小写形式。 */
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md");

    private final Path root;

    public LocalDocumentStorage(@Value("${teammind.document.storage-path:./data/documents}") String path) {
        this.root = Path.of(path).toAbsolutePath().normalize();
    }

    public StoredDocument store(MultipartFile file, Long documentId, int version) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件名不能为空");
        }
        // 兼容浏览器提交的 Windows 路径，并阻止路径穿越字符参与文件命名。
        String fileName = Path.of(name.replace('\\', '/')).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只支持 PDF、DOCX、TXT 和 Markdown 文件");
        }
        try {
            Path directory = root.resolve(String.valueOf(documentId)).normalize();
            if (!directory.startsWith(root)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件路径不合法");
            }
            Files.createDirectories(directory);
            Path target = directory.resolve("v" + version + "." + extension);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredDocument(fileName, extension, target);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件保存失败", exception);
        }
    }

    public InputStream open(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(root)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文档存储路径越界");
            }
            return Files.newInputStream(normalized);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法读取文档", exception);
        }
    }

    public record StoredDocument(String name, String extension, Path path) {
    }
}
