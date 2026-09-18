package com.example.springai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 到期提醒这一轮的结果（给日志和验证用，不对外暴露）。 */
@Data
public class CompanyReminderResult {

    /** 扫描到的"正常 + 有到期日"的公司数。 */
    private int scanned;

    /** 命中提醒窗口、需要提醒的公司。 */
    private List<Hit> hits = new ArrayList<>();

    /** 内部提醒邮件成功的收件人数。0 表示没配收件人或全失败。 */
    private int internalSent;

    /** 客户管理员邮件成功的数量。 */
    private int clientSent;

    /** 客户管理员邮件失败/跳过的明细（用于日志提醒人工跟进）。 */
    private List<String> clientIssues = new ArrayList<>();

    /** 是否已把命中公司的"已提醒"标记落库。 */
    private boolean marked;

    @Data
    public static class Hit {
        private Long companyId;
        private String companyName;
        /** 距到期天数（0=今天到期）。 */
        private Integer daysLeft;
        /** 30 / 7 —— 命中哪一档。 */
        private Integer stage;
        private String expireDate;
        private String contractNote;
        /** 已启用账号数 / 席位上限（上限为空表示不限）。 */
        private long activeMembers;
        private Integer seatLimit;

        public Hit(Long companyId, String companyName, Integer daysLeft, Integer stage) {
            this.companyId = companyId;
            this.companyName = companyName;
            this.daysLeft = daysLeft;
            this.stage = stage;
        }
    }
}
