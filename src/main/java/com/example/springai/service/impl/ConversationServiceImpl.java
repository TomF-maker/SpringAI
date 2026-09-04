package com.example.springai.service.impl;

import com.example.springai.entity.Conversation;
import com.example.springai.entity.Message;
import com.example.springai.repository.ConversationRepository;
import com.example.springai.service.ConversationServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConversationServiceImpl implements ConversationServiceI {

    @Autowired
    private ConversationRepository conversationRepository;

    public Conversation createConversation(Long userId, String firstQuestion) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle(generateTitle(firstQuestion));
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(conv);
    }

    public Conversation addMessage(String conversationId, String role, String content) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("会话不存在"));
        conv.getMessages().add(new Message(role, content, LocalDateTime.now()));
        conv.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(conv);
    }

    public List<Conversation> getUserConversations(Long userId) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    public Conversation getConversation(String id) {
        return conversationRepository.findById(id).orElse(null);
    }

    public void deleteConversation(String id) {
        conversationRepository.deleteById(id);
    }

    private String generateTitle(String firstQuestion) {
        if (firstQuestion == null || firstQuestion.isEmpty()) {
            return "新对话";
        }
        // 截取前20个字符作为标题
        return firstQuestion.length() > 20 ? firstQuestion.substring(0, 20) + "..." : firstQuestion;
    }
}