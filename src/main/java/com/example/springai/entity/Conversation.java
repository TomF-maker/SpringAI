package com.example.springai.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "conversations")
public class Conversation {
    @Id
    private String id;
    private Long userId;

    /**
     * 会话归属的客户公司（{@code sys_company.id}）。
     *
     * <p>和 {@link KbDocument#getClientId()} 同一套隔离语义：会话内容属于哪家
     * 客户。取自创建会话时用户的 {@code sys_user.company_id}，匿名用户为 null。
     *
     * <p>P0 阶段会话列表仍按 {@code userId} 隔离（用户只看自己的会话），
     * 本字段是 P1「客户管理员后台」的铺路 —— 届时客户管理员要按本公司
     * {@code clientId} 聚合看员工活跃、热门问题，没有这个字段就查不出来。
     */
    private Long clientId;

    private String title;
    private List<Message> messages = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}