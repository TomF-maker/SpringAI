package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springai.dto.CompanyDocumentDTO;
import com.example.springai.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    @Select("SELECT DATE(created_at) as date, COUNT(*) as count FROM kb_document WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) GROUP BY DATE(created_at) ORDER BY date ASC")
    List<Map<String, Object>> selectDailyUploads(@Param("days") int days);

    @Select("SELECT file_type, COUNT(*) as count FROM kb_document GROUP BY file_type")
    List<Map<String, Object>> selectFileTypeDistribution();

    /**
     * 某家客户最近的文档（公司详情下钻用）。
     *
     * <p>只取该客户**专属**的文档，不含通用池 —— 详情页要回答的是
     * "这家客户的资料齐不齐"，把通用方法论混进来会让这个数字失去意义
     * （通用文档每家都能看到，不体现这家的情况）。
     */
    @Select("""
            SELECT id, title, file_name, chunk_count, status, created_at
            FROM kb_document
            WHERE client_id = #{clientId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<CompanyDocumentDTO> selectRecentByClient(@Param("clientId") Long clientId,
                                                 @Param("limit") int limit);
}