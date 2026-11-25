package com.teammind.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.domain.Document;
import com.teammind.knowledge.domain.KnowledgeBase;
import com.teammind.knowledge.domain.ProcessingStatus;
import com.teammind.knowledge.mapper.DocumentMapper;
import com.teammind.knowledge.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper, DocumentMapper documentMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
    }

    @Transactional
    public KnowledgeBase create(String name, String description, Long ownerId) {
        String normalizedName = name.trim();
        ensureNameAvailable(ownerId, normalizedName, null);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setName(normalizedName);
        knowledgeBase.setDescription(description);
        knowledgeBase.setOwnerId(ownerId);
        knowledgeBase.setStatus(1);
        knowledgeBase.setDeleted(false);
        knowledgeBase.setCreatedAt(LocalDateTime.now());
        knowledgeBase.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase;
    }

    public List<KnowledgeBase> list(Long ownerId) {
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getOwnerId, ownerId).orderByDesc(KnowledgeBase::getCreatedAt));
    }

    public KnowledgeBase get(Long id, Long ownerId) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(id);
        if (knowledgeBase == null || !Integer.valueOf(1).equals(knowledgeBase.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        // Phase 2 尚未接入登录，此处使用显式归属参数完成最小边界校验，不能替代身份认证。
        if (!knowledgeBase.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        return knowledgeBase;
    }

    @Transactional
    public KnowledgeBase update(Long id, String name, String description, Long ownerId) {
        KnowledgeBase knowledgeBase = get(id, ownerId);
        String normalizedName = name.trim();
        ensureNameAvailable(ownerId, normalizedName, id);
        knowledgeBase.setName(normalizedName);
        knowledgeBase.setDescription(description);
        knowledgeBase.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.updateById(knowledgeBase);
        return knowledgeBase;
    }

    @Transactional
    public void delete(Long id, Long ownerId) {
        get(id, ownerId);
        Long count = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, id));
        if (count > 0) {
            throw new BusinessException("知识库中仍有文档，请先删除文档");
        }
        // @TableLogic 字段不会出现在 updateById 的 SET 子句中，必须使用框架逻辑删除更新 deleted。
        knowledgeBaseMapper.deleteById(id);
    }

    public List<KnowledgeBase> getAccessible(List<Long> ids, Long ownerId) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少选择一个知识库");
        }
        if (ids.size() != ids.stream().collect(Collectors.toSet()).size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "知识库不能重复");
        }
        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .in(KnowledgeBase::getId, ids).eq(KnowledgeBase::getOwnerId, ownerId)
                .eq(KnowledgeBase::getStatus, 1));
        Set<Long> found = knowledgeBases.stream().map(KnowledgeBase::getId).collect(Collectors.toSet());
        if (!found.equals(Set.copyOf(ids))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在或不可访问");
        }
        return knowledgeBases;
    }

    private void ensureNameAvailable(Long ownerId, String name, Long excludedId) {
        LambdaQueryWrapper<KnowledgeBase> query = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getOwnerId, ownerId).eq(KnowledgeBase::getName, name);
        if (excludedId != null) {
            query.ne(KnowledgeBase::getId, excludedId);
        }
        if (knowledgeBaseMapper.selectCount(query) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一用户下知识库名称不能重复");
        }
    }
}
