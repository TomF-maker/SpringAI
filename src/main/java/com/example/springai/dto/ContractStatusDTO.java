package com.example.springai.dto;

import lombok.Data;

/**
 * 当前登录用户所属公司的服务状态（站内横幅用）。
 *
 * <p><b>刻意不含 `contract_note`</b>：那是内部备注（年费、条款要点），
 * 给客户看到是事故。这个 DTO 会被前端渲染进页面，所以字段只能包含
 * "客户自己也知道的"信息。
 */
@Data
public class ContractStatusDTO {

    /** false = 这个账号不挂在任何客户公司下（内部账号），前端不显示横幅。 */
    private boolean applicable;

    private String companyName;

    /** 到期日 yyyy-MM-dd；null = 不限（未签或长期）。 */
    private String expireDate;

    /** 距到期天数：0=今天到期，负数=已过期，null=不限。 */
    private Integer daysLeft;

    /** 是否进入提醒窗口（30 天内，含今天）。 */
    private boolean expiringSoon;
}
