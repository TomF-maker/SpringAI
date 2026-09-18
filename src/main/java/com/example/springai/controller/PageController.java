package com.example.springai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String root() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /**
     * 开通账号说明页（原来的注册页）。
     *
     * <p>自助注册已关闭（A4 开户收口，见 {@code AuthController.register}），
     * 这里只渲染"联系我们开通"的说明 —— 不再往模板传 {@code smsEnabled}：
     * 那个标志位是给注册表单决定"手机号还是邮箱验证码"用的，表单没了，
     * 传下去只会让人以为这页还有表单逻辑。
     */
    @GetMapping("/register")
    public String register() {
        return "register";
    }

    /**
     * 忘记密码：邮箱验证码重置。
     *
     * <p>不需要 smsEnabled —— 这条流程走的是**邮箱**验证码，和短信总开关无关。
     * 但注意：短信开启时注册只要求手机号、邮箱变成可选，那类没填邮箱的账号
     * 走不了这条路，只能找管理员重置。
     */
    @GetMapping("/forgot-password")
    public String forgotPassword() {
        return "forgot-password";
    }

    @GetMapping("/chat")
    public String chat() {
        return "chat";
    }

    @GetMapping("/users")
    public String users() {
        return "users";
    }

    @GetMapping("/documents")
    public String documents() {
        return "documents";
    }

    /**
     * 公司管理（开户 / 合约 / 停用 / 指定客户管理员）。
     *
     * <p>页面照例在白名单里放行，真正的闸门在 {@code /api/companies}
     * 的类级 {@code @PreAuthorize("hasRole('ADMIN')")} —— 非内部管理员打开
     * 只会看到一个空壳。
     */
    @GetMapping("/companies")
    public String companies() {
        return "companies";
    }

    @GetMapping("/profile")
    public String profile() {
        return "profile";
    }

    /**
     * 修改初始密码（xlsx 批量导入的账号首次登录必须先来这里）。
     *
     * <p>独立页面而不是复用个人中心的改密弹窗：这批用户改密前**什么接口都调不了**
     * （见 {@code PasswordChangeGuardFilter}），个人中心那些资料卡片会全部报错，
     * 页面上只有一片错误提示。这里只留一个改密表单，没有别的可点。
     */
    @GetMapping("/change-password")
    public String changePassword() {
        return "change-password";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard";
    }

    /**
     * 客户公司看板（客户管理员看自己的公司）。
     *
     * <p>页面本身与其它后台页一样放行（SecurityConfig 白名单），真正的数据闸门在
     * {@code /api/client-admin/stats} 的 {@code @PreAuthorize("hasRole('CLIENT_ADMIN')")}——
     * 非客户管理员打开这个页面只会看到一个"无权访问"的空壳，拿不到任何数字。
     */
    @GetMapping("/client-admin")
    public String clientAdmin() {
        return "client-admin";
    }

    /**
     * 地域大屏（登录 / 提问分别来自哪个省、市）。
     *
     * <p>全屏深色页，刻意不带 sidebar/topbar 片段，也不引 app.css / app.js ——
     * 那两个是给带侧边栏的后台页面用的，在全屏投屏页上既用不上又可能打架。
     */
    @GetMapping("/screen")
    public String screen() {
        return "screen";
    }

    @GetMapping("/history")
    public String history() {
        return "history";
    }

    @GetMapping("/feedback-review")
    public String feedbackReview() {
        return "feedback-review";
    }
}