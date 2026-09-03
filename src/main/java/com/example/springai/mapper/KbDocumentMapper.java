package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}