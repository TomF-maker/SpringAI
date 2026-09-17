package com.example.springai.controller;

import com.example.springai.common.ErrorCode;
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
        Conversation conv = conversationService.createConversation(user.getId(), question, user.getCompanyId());
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

    /**
     * 会话详情。
     *
     * <p>必须带 Authentication 做归属校验 —— 这个参数就是权限本身。
     * 早先没有它，任何登录用户拿到别人的 id 就能读整段对话。
     * 不属于本人时统一回"会话不存在"，不暴露"存在但你没权限"。
     */
    @GetMapping("/{id}")
    public Response<Conversation> getConversation(@PathVariable String id,
                                                  Authentication authentication) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        Conversation conv = conversationService.getConversation(id, user.getId());
        if (conv == null) {
            return Response.fail(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return Response.success(conv);
    }

    @DeleteMapping("/{id}")
    public Response<Void> deleteConversation(@PathVariable String id,
                                             Authentication authentication) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        if (!conversationService.deleteConversation(id, user.getId())) {
            return Response.fail(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return Response.success();
    }
}