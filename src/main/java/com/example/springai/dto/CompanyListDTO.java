package com.example.springai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公司管理列表的一行。
 *
 * <p>带上三个用量数字（员工数 / 文档数 / 近 30 天提问数）：这是内部管理员扫一眼
 * 就能判断"这家是不是快到期了、是不是根本没在用"的信息 —— 续费风险全在这三个数上。
 * 它们由一条聚合 SQL 一次取回（{@code SysCompanyMapper.selectCompanyOverview}），
 * 不做 N+1：50 家客户就是 150 次查询。
 */
@Data
public class CompanyListDTO {
    private Long id;
    private String companyName;
    private String creditCode;
    private Integer status;
    private LocalDateTime contractExpireAt;
    private Integer seatLimit;
    private String contractNote;

    private Long memberCount;
    private Long documentCount;
    private Long questions30d;

    /** 当前是否被拦（停用或已到期）。前端据此把状态渲染成"停用/已到期"而不是"正常"。 */
    private Boolean blocked;
    /** 被拦的原因，直接显示给内部管理员看（与员工登录时看到的是同一句话）。 */
    private String blockReason;

    /** 距到期天数：null=不限、0=今天到期、负数=已过期。 */
    private Integer daysLeft;
    /** 是否进入"即将到期"窗口（30 天内，含今天）。 */
    private Boolean expiringSoon;
}
