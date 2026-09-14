package com.example.springai.controller;

import com.example.springai.common.Response;
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
    public Response<Map<String, Object>> createConversation(Authentication authentication,
                                                            @RequestParam String question) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        Conversation conv = conversationService.createConversation(user.getId(), question);
        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", conv.getId());
        data.put("title", conv.getTitle());
        return Response.success(data);
    }

    @GetMapping
    public Response<List<Conversation>> getUserConversations(Authentication authentication) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        return Response.success(conversationService.getUserConversations(user.getId()));
    }

    @GetMapping("/{id}")
    public Response<Conversation> getConversation(@PathVariable String id) {
        return Response.success(conversationService.getConversation(id));
    }

    @DeleteMapping("/{id}")
    public Response<Void> deleteConversation(@PathVariable String id) {
        conversationService.deleteConversation(id);
        return Response.success();
    }
}