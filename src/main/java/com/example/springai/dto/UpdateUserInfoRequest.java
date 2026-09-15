package com.example.springai.dto;

import lombok.Data;

@Data
public class UpdateUserInfoRequest {
    private String realName;
    private String phone;
    private String email;

    /**
     * 所属公司名称。<b>与 creditCode 要么都传、要么都不传</b> ——
     * 公司是按信用代码 find-or-create 的，只给名称没法定位、只给代码没法新建。
     * 两个都不传表示不改公司。老用户两项都是空的，整个不传即可。
     */
    private String companyName;
    /** 统一社会信用代码。与 companyName 成对出现，见上。 */
    private String creditCode;
}