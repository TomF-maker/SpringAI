package com.example.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor; // 1. 替换为 MessageChatMemoryAdvisor
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory; // 2. 新的记忆实现类
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository; // 3. 新的内存仓库
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemoryChatController {

    private final ChatClient chatClient;

    public MemoryChatController(ChatClient.Builder chatClientBuilder) {
        // 1. 创建内存仓库
        InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();

        // 2. 创建 ChatMemory，设置窗口大小为10条消息
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(10) // 保留最近10条消息
                .build();

        // 3. 将 MessageChatMemoryAdvisor 设置为默认顾问
        this.chatClient = chatClientBuilder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @GetMapping("/ai/memory")
    public String memoryChat(@RequestParam String sessionId,
                             @RequestParam String message) {
        // 4. 每次请求时，通过 .advisors() 传入 conversation_id
        return chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param("chat_memory_conversation_id", sessionId))
                .call()
                .content();
    }
}