package com.example.springai.controller;

import com.example.springai.entity.Conversation;
import com.example.springai.entity.SysUser;
import com.example.springai.service.ConversationServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    @Autowired
    private ConversationServiceI conversationService;

    @Autowired
    private UserServiceI userService;

    @PostMapping
    public Map<String, Object> createConversation(Authentication authentication,
                                                  @RequestParam String question) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        Conversation conv = conversationService.createConversation(user.getId(), question);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("conversationId", conv.getId());
        result.put("title", conv.getTitle());
        return result;
    }

    @GetMapping
    public List<Conversation> getUserConversations(Authentication authentication) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        return conversationService.getUserConversations(user.getId());
    }

    @GetMapping("/{id}")
    public Conversation getConversation(@PathVariable String id) {
        return conversationService.getConversation(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteConversation(@PathVariable String id) {
        conversationService.deleteConversation(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "删除成功");
        return result;
    }
}