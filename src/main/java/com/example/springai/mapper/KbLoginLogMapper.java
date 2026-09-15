package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springai.entity.KbLoginLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录审计日志。目前只有写入，没有查询 —— BI 的按省聚合等真正要做报表时再加
 * {@code @Select}（沿用 {@code KbQuestionLogMapper} 那种显式列清单的写法）。
 */
@Mapper
public interface KbLoginLogMapper extends BaseMapper<KbLoginLog> {
}
