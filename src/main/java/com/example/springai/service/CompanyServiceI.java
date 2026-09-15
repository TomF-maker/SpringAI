package com.example.springai.service;

import com.example.springai.entity.SysCompany;
import com.example.springai.exception.BizException;

/**
 * 公司：按信用代码找到或创建。
 *
 * <p>公司是独立实体（{@code sys_company}），用户通过 {@code company_id} 挂靠。
 * 同事用同一个信用代码注册时会自动落到同一条记录上 —— 这是把它独立成表的全部意义。
 */
public interface CompanyServiceI {

    /**
     * 按信用代码查找公司，没有就创建一条。
     *
     * <p><b>以信用代码为准，公司名只在创建时写入一次。</b>同一代码下后来的人填了
     * 不一样的写法（「XX科技」vs「XX科技有限公司」）不会覆盖已有名称 ——
     * 让最后一个注册的人决定全公司的显示名会导致名称来回跳。
     * 名称不一致时只打一条 WARN 提示可能有人填错。
     *
     * @param companyName 用户填的公司名，前后空白会被裁掉
     * @param creditCode  统一社会信用代码，会先归一化再验校验位
     * @return 公司 id
     * @throws BizException 名称或信用代码不合法时
     */
    Long resolveOrCreate(String companyName, String creditCode);

    /** 按 id 查公司，不存在或 id 为 null 时返回 null。用于把 id 翻译成展示用的名称与代码。 */
    SysCompany findById(Long companyId);
}
