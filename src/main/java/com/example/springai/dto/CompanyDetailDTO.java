package com.example.springai.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公司详情（点开某一家客户）。
 *
 * <p>四块内容：合约与状态、员工名单、最近文档、用量。
 * <p>用量那块**直接复用客户看板的数据结构**（{@link ClientAdminStatsDTO}）——
 * 客户自己看到的和我们看到的必须是同一套数字，否则客户打电话来说"我这个月明明问了 50 次"
 * 时，两边对不上就没法解释。区别只在于 clientId 的来源：客户侧来自登录用户，
 * 这里来自管理员选中的公司。
 */
@Data
public class CompanyDetailDTO {
    private Long id;
    private String companyName;
    private String creditCode;
    private Integer status;
    private LocalDateTime contractExpireAt;
    private Integer seatLimit;
    private String contractNote;
    private LocalDateTime createdAt;

    private Long memberCount;
    private Long activeMemberCount;
    private Long documentCount;

    private Boolean blocked;
    private String blockReason;

    private List<CompanyMemberDTO> members;
    private List<CompanyDocumentDTO> documents;

    /** 近 N 天用量（客户看板同款）。建表/加列未就绪时 {@code unavailable=true}。 */
    private ClientAdminStatsDTO stats;
}
