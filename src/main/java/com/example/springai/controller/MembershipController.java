package com.example.springai.controller;

import com.example.springai.common.Response;
import com.example.springai.dto.MembershipStatusDTO;
import com.example.springai.entity.SysUser;
import com.example.springai.service.MembershipServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会员（登录用户）。
 *
 * <p>路径不在 permitAll 里，{@code SecurityConfig} 的 {@code anyRequest().authenticated()}
 * 自动要求登录，不用额外配置。
 *
 * <p>会员状态刻意独立成一个接口，而不是塞进 {@code /api/users/me} 的 DTO ——
 * 那边的 {@code UserInfoDTO} 走 {@code BeanUtils.copyProperties}，
 * 塞派生字段（是否有效、剩余天数）要么加一个和实体不同名的字段、要么让
 * {@code UserServiceImpl} 反向依赖会员服务，都不如一个独立接口干净。
 */
@Slf4j
@RestController
@RequestMapping("/api/membership")
public class MembershipController {

    @Autowired
    private MembershipServiceI membershipService;

    @Autowired
    private UserServiceI userService;

    /** 我的会员状态 + 积分 + 免费额度。 */
    @GetMapping("/me")
    public Response<MembershipStatusDTO> me(Authentication authentication) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        return Response.success(membershipService.getStatus(user == null ? null : user.getId()));
    }

    /** 可兑换档位。价格从后端来，前端不硬编码。 */
    @GetMapping("/plans")
    public Response<List<Map<String, Object>>> plans() {
        return Response.success(membershipService.listPlans());
    }

    /**
     * 用积分兑换会员。
     *
     * <p>兑换的幂等与并发安全都在服务层：扣分带 {@code points >= ?} 守卫，
     * 会员字段走 CAS，冲突时整个事务回滚（连扣分一起退）。
     */
    @PostMapping("/exchange")
    public Response<MembershipStatusDTO> exchange(Authentication authentication,
                                                  @RequestBody Map<String, String> body) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        if (user == null) {
            return Response.fail(com.example.springai.common.ErrorCode.UNAUTHORIZED, "用户不存在，请重新登录");
        }
        MembershipStatusDTO status = membershipService.exchange(user.getId(), body.get("plan"));
        return Response.success(status);
    }
}
