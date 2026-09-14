package com.example.springai.controller;

import com.example.springai.common.Response;
import com.example.springai.dto.FeedbackRequest;
import com.example.springai.entity.SysUser;
import com.example.springai.service.AnswerFeedbackServiceI;
import com.example.springai.service.PointsServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 答案评价（登录用户可用）。
 *
 * <p>路径不在 permitAll 里，所以匿名用户天然提交不了 —— 这是刻意的：
 * 匿名的提问没有可靠的身份，无法防止刷评价、也无从发放积分。
 */
@Slf4j
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    @Autowired
    private AnswerFeedbackServiceI feedbackService;

    @Autowired
    private PointsServiceI pointsService;

    @Autowired
    private UserServiceI userService;

    /** 五档评价选项，供前端渲染按钮。放这里是为了让前端不用把中文标签硬编码一份。 */
    @GetMapping("/options")
    public Response<List<Map<String, Object>>> options() {
        return Response.success(feedbackService.ratingOptions());
    }

    /** 提交评价（低分档可附带优化意见）。 */
    @PostMapping
    public Response<Map<String, Object>> submit(Authentication authentication,
                                                @RequestBody FeedbackRequest request) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        feedbackService.submit(user.getId(), request);

        Map<String, Object> data = new HashMap<>();
        data.put("points", pointsService.getBalance(user.getId()));
        return Response.success(data);
    }
}
