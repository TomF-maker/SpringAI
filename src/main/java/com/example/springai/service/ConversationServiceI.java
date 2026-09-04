package com.example.springai.service;

import com.example.springai.entity.Conversation;

import java.util.List;

public interface ConversationServiceI {
    Conversation createConversation(Long userId, String firstQuestion);

    Conversation addMessage(String conversationId, String role, String content);

    List<Conversation> getUserConversations(Long userId);

    Conversation getConversation(String id);

    void deleteConversation(String id);
}
