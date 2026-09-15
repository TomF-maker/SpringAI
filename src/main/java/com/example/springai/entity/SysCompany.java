package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公司。用户通过 {@code sys_user.company_id} 挂靠到这里。
 *
 * <p><b>为什么公司要独立成表，而不是在 sys_user 上存一个公司名字符串</b>：
 * 第一版就是那样做的，结果是"一家公司只能注册进去一个人" —— 因为信用代码
 * 是**公司的属性**，在用户表上对它做唯一约束，等于假设"一个代码只对应一个人"。
 * 同事之间的第二次注册会被自己人挡在门外，而报错还说"该信用代码已被注册"，
 * 他根本看不出是自己同事先注册了。
 *
 * <p>独立成表之后同事自动落到同一条公司记录上，唯一约束也落在它该在的地方
 * （信用代码唯一）。"每公司多少人"变成一次干净的 JOIN。
 *
 * <p>注册时按信用代码 find-or-create，见 {@code CompanyService.resolveOrCreate}。
 * 信用代码的格式与校验位校验在 {@code common.CreditCode}。
 */
@Data
@TableName("sys_company")
public class SysCompany {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 公司名称。取自**第一个**用这个信用代码注册的人填的写法。 */
    private String companyName;

    /** 统一社会信用代码（GB 32100，18 位）。全局唯一 —— 这是公司的身份，不是人的。 */
    private String creditCode;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
