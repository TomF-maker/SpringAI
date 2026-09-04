package com.example.springai.repository;

import com.example.springai.entity.Conversation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface ConversationRepository extends MongoRepository<Conversation, String> {

    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);

    @Query(value = "{ 'userId': ?0 }", fields = "{ 'messages': 0 }")
    List<Conversation> findConversationSummariesByUserId(Long userId);
}