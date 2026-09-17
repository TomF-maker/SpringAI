package com.example.springai.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    private String role;      // user / assistant
    private String content;
    private LocalDateTime timestamp;
    /**
     * 答案来源标注（仅 assistant 消息且检索命中时有值）。
     *
     * <p>持久化到 MongoDB 的会话消息里，这样用户从历史记录点进去仍能看到
     * 「来源」展开区 —— 否则来源只在当次回答的返回体里，刷新/重进就丢了。
     */
    private List<SourceRef> sources;
}