package com.example.springai.service;

import com.example.springai.dto.UserImportResultDTO;
import org.springframework.web.multipart.MultipartFile;

/**
 * xlsx 批量导入平台用户（给客户公司开一批账号）。
 *
 * <p><b>只该给内部管理员用。</b>这是"开户"动作，不是客户自助注册 ——
 * 客户方不该能给自己公司加人（那等于绕过伯伯公司的开户与计费）。
 */
public interface UserImportServiceI {

    /**
     * 从 xlsx 导入用户，全部挂到指定客户公司下。
     *
     * <p>逐行独立处理：某一行失败不影响其他行，结果里逐行给出原因。
     * 刻意**不加事务** —— 一个大事务会让一行失败回滚整批，
     * 而管理员要的是"能成的先成，不能成的告诉我为什么"。
     *
     * @param file      xlsx 文件（只读第一个工作表，表头必须在第 1 行）
     * @param companyId 整批归属的客户公司（{@code sys_company.id}），必填
     * @return 逐行结果
     */
    UserImportResultDTO importUsers(MultipartFile file, Long companyId);
}
