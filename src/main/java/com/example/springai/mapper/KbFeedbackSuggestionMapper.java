package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.springai.dto.PendingSuggestionDTO;
import com.example.springai.entity.KbFeedbackSuggestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KbFeedbackSuggestionMapper extends BaseMapper<KbFeedbackSuggestion> {

    /**
     * 管理端审核列表：把建议、评价、提问日志、提建议的人一次性拼好。
     *
     * <p>为什么要 join 提问日志：管理员判断"这条建议合不合理"，必须看到原问题和原回答，
     * 而且 {@code hit_type}（是没检索到、还是检索到了但答得不好）直接决定该补文档
     * 还是该改提示词 —— 只看一句"回答不准确"是没法判断的。
     *
     * <p>第一个参数是 {@code IPage}，MyBatis-Plus 的分页拦截器会自动改写 SQL 并回填 total。
     * {@code status} 传 null 表示查全部。
     *
     * <p>{@code keyword} 是**已经拼好通配符并转义过**的 LIKE 模式（如 {@code %关键词%}），
     * 不是用户原文 —— 拼装和转义都在 service 里做，SQL 这层只负责比较。
     * 转义符固定用 {@code !}：反斜杠在 Java text block 和 MySQL 字符串里都要再转一层，
     * 写成 {@code ESCAPE '\\'} 极易搞错，换个字符就没这个坑。
     */
    @Select("""
            SELECT s.id, s.content, s.status, s.awarded_points, s.review_remark,
                   s.reviewed_at, s.created_at,
                   s.user_id, u.username, u.real_name, u.points AS user_points,
                   f.id AS feedback_id, f.question_log_id, f.conversation_id,
                   f.question, f.answer, f.rating,
                   q.hit_type, q.retrieved_count, q.latency_ms
            FROM kb_feedback_suggestion s
            LEFT JOIN kb_answer_feedback f ON f.id = s.feedback_id
            LEFT JOIN sys_user u ON u.id = s.user_id
            LEFT JOIN kb_question_log q ON q.id = f.question_log_id
            WHERE (#{status} IS NULL OR s.status = #{status})
              AND (#{keyword} IS NULL OR s.content LIKE #{keyword} ESCAPE '!')
            ORDER BY s.created_at DESC, s.id DESC
            """)
    List<PendingSuggestionDTO> selectSuggestions(IPage<PendingSuggestionDTO> page,
                                                 @Param("status") String status,
                                                 @Param("keyword") String keyword);

    /** 待审核条数，用于菜单上的角标/提示。 */
    @Select("SELECT COUNT(*) FROM kb_feedback_suggestion WHERE status = 'PENDING'")
    long countPending();
}
