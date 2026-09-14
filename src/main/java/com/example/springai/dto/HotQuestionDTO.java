package com.example.springai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HotQuestionDTO {
    /** 代表性原始问题（同一归一化分组里取一个样本展示）。 */
    private String question;
    private long count;
    /** 最近一次被问到的时间。 */
    private String lastAskedAt;
}
