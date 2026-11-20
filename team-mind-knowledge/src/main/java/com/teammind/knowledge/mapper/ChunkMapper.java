package com.teammind.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teammind.knowledge.domain.Chunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChunkMapper extends BaseMapper<Chunk> {
    /**
     * PostgreSQL 内计算标准 BM25。查询词元由应用转义并拼成 OR 查询，
     * 文档频率、平均长度和得分均只在当前用户选择的有效知识范围内计算。
     */
    @Select("""
            <script>
            WITH corpus AS (
                SELECT c.id, c.search_tokens, c.search_vector,
                       cardinality(string_to_array(c.search_tokens, ' ')) AS document_length
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                JOIN knowledge_base kb ON kb.id = c.knowledge_base_id
                WHERE c.knowledge_base_id IN
                <foreach collection='knowledgeBaseIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
                  AND kb.owner_id = #{ownerId} AND NOT kb.deleted AND kb.status = 1
                  AND NOT d.deleted AND d.processing_status = 'COMPLETED'
                  AND d.current_version = c.document_version
                  AND c.search_tokens IS NOT NULL AND c.search_tokens != ''
            ), stats AS (
                SELECT count(*) AS document_count, avg(document_length) AS average_length FROM corpus
            ), query_terms AS (
                SELECT DISTINCT term FROM unnest(string_to_array(#{queryTerms}, ' ')) AS term
                WHERE term != '' LIMIT #{maxTermCount}
            ), document_frequency AS (
                SELECT q.term, count(*) AS frequency
                FROM query_terms q JOIN corpus c ON c.search_vector @@ to_tsquery('simple', q.term)
                GROUP BY q.term
            ), document_terms AS (
                SELECT c.id, q.term, count(*) AS term_frequency
                FROM corpus c CROSS JOIN query_terms q
                CROSS JOIN LATERAL unnest(string_to_array(c.search_tokens, ' ')) AS token
                WHERE token = q.term
                GROUP BY c.id, q.term
            )
            SELECT dt.id, SUM(
                ln(1 + (s.document_count - df.frequency + 0.5) / (df.frequency + 0.5))
                * (dt.term_frequency * 2.2)
                / (dt.term_frequency + 1.2 * (0.25 + 0.75 * c.document_length / s.average_length))
            ) AS score
            FROM document_terms dt
            JOIN document_frequency df ON df.term = dt.term
            JOIN corpus c ON c.id = dt.id
            CROSS JOIN stats s
            WHERE c.search_vector @@ to_tsquery('simple', #{query})
            GROUP BY dt.id
            ORDER BY score DESC, dt.id
            LIMIT #{limit}
            </script>
            """)
    List<Bm25Hit> searchBm25(@Param("query") String query, @Param("queryTerms") String queryTerms,
            @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds, @Param("ownerId") Long ownerId,
            @Param("limit") int limit, @Param("maxTermCount") int maxTermCount);

    record Bm25Hit(Long id, double score) {
    }
}
