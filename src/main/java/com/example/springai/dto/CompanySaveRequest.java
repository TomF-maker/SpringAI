package com.example.springai.dto;

import lombok.Data;

/** 新建 / 编辑公司的请求体。 */
@Data
public class CompanySaveRequest {

    private String companyName;

    /** 统一社会信用代码。新建时校验格式与唯一性；编辑时允许改（客户可能填错过）。 */
    private String creditCode;

    /**
     * 合约到期日，形如 {@code 2026-12-31}（前端 {@code <input type="date">} 的格式）。
     *
     * <p>用字符串而不是 {@code LocalDateTime}：日期输入框没有时分秒，Jackson 反序列化
     * "2026-12-31" 到 LocalDateTime 会直接失败。这里由 service 解析成
     * **当天 23:59:59** —— 到期日当天客户还能用，第二天才拦（写成 00:00:00
     * 等于把最后一天吃掉，客户会觉得少了一天）。空串/null = 不限。
     */
    private String contractExpireAt;

    /** 席位上限（可启用账号数）。null 或 < 0 = 不限。 */
    private Integer seatLimit;

    private String contractNote;
}
