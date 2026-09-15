package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.common.CreditCode;
import com.example.springai.entity.SysCompany;
import com.example.springai.exception.BizException;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.service.CompanyServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CompanyService implements CompanyServiceI {

    private static final int MAX_NAME_LENGTH = 128;

    @Autowired
    private SysCompanyMapper companyMapper;

    @Override
    public Long resolveOrCreate(String companyName, String creditCode) {
        String name = validateName(companyName);
        String code = CreditCode.normalize(creditCode);
        if (!CreditCode.isValid(code)) {
            throw new BizException("统一社会信用代码不正确（应为 18 位且校验位有效）");
        }

        SysCompany existing = findByCreditCode(code);
        if (existing != null) {
            warnIfNameDiffers(existing, name);
            return existing.getId();
        }

        SysCompany company = new SysCompany();
        company.setCompanyName(name);
        company.setCreditCode(code);
        try {
            companyMapper.insert(company);
            return company.getId();
        } catch (DuplicateKeyException e) {
            // 并发：两个同事几乎同时用同一个**新**信用代码注册，
            // 两边都没查到、都去插入，唯一索引只放行一个。
            // 这不是错误 —— 重新查一次就能拿到对方刚建好的那条，然后复用。
            // 少了这段的话，其中一个会看到"注册失败"，而失败原因跟他的输入毫无关系。
            SysCompany raced = findByCreditCode(code);
            if (raced == null) {
                // 不是并发造成的重复，那就是别的问题，别把异常吞掉
                throw e;
            }
            log.info("并发注册同一家公司，复用已存在的记录: creditCode={}", code);
            return raced.getId();
        }
    }

    @Override
    public SysCompany findById(Long companyId) {
        return companyId == null ? null : companyMapper.selectById(companyId);
    }

    // ==================== 内部 ====================

    private SysCompany findByCreditCode(String creditCode) {
        // 唯一索引保证最多一条，但 LIMIT 1 是防御：真出现脏数据时
        // selectOne 会抛 TooManyResultsException，注册链路不该被它打断
        return companyMapper.selectOne(
                new QueryWrapper<SysCompany>().eq("credit_code", creditCode).last("LIMIT 1"));
    }

    private static String validateName(String companyName) {
        String name = companyName == null ? null : companyName.trim();
        if (name == null || name.isEmpty()) {
            throw new BizException("请填写所属公司名称");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BizException("公司名称过长（最多 " + MAX_NAME_LENGTH + " 字）");
        }
        return name;
    }

    /**
     * 信用代码相同但公司名写法不同时只提示，不拒绝也不改数据。
     *
     * <p>不改是因为：如果让后来者覆盖，全公司的显示名就由**最后一个注册的人**决定，
     * 几个同事先后注册会把名字改来改去。
     * 不拒绝是因为：多半只是有人多打了「有限公司」几个字，为这个把同事挡在门外不值得。
     */
    private static void warnIfNameDiffers(SysCompany existing, String incoming) {
        if (!existing.getCompanyName().equals(incoming)) {
            log.warn("⚠️ 信用代码 {} 已登记为「{}」，本次注册填的是「{}」，"
                            + "沿用已有名称。若确属不同公司请核对信用代码",
                    existing.getCreditCode(), existing.getCompanyName(), incoming);
        }
    }
}
