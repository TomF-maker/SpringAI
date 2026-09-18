package com.example.springai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * xlsx 批量导入用户的结果。
 *
 * <p><b>逐行返回，而不是只给一句"成功 N 条失败 M 条"</b>：导入是管理员一次几十行的操作，
 * 失败的那几行必须能对上 Excel 里的行号、看到具体原因，否则他只能整份重来。
 * 表头行也算在 {@code rowNum} 里（第 1 行是表头，数据从第 2 行开始），
 * 这样行号和 Excel 左侧显示的数字一致。
 */
@Data
public class UserImportResultDTO {

    /** 解析到的数据行数（不含表头）。 */
    private int total;
    private int succeeded;
    private int failed;

    /** true 表示整个文件都没法处理（表头缺列、格式不对），此时 rows 为空。 */
    private boolean fatal;

    /** fatal 时的原因，以及整体提示（如"文件内重复用户名"）。 */
    private String message;

    private List<Row> rows = new ArrayList<>();

    /** 单行结果。 */
    @Data
    @AllArgsConstructor
    public static class Row {
        /** Excel 行号（1-based，与 Excel 左侧一致）。 */
        private int rowNum;
        private String username;
        private String realName;
        /** OK / FAIL。 */
        private String state;
        /** 失败原因；成功时是补充说明（如"已挂到总公司"）。 */
        private String message;
        /** 成功时的新用户 id。 */
        private Long userId;
    }

    public static final String OK = "OK";
    public static final String FAIL = "FAIL";
}
