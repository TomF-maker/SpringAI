package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.common.EmailFormat;
import com.example.springai.common.ErrorCode;
import com.example.springai.dto.UserImportResultDTO;
import com.example.springai.entity.SysCompany;
import com.example.springai.entity.SysUser;
import com.example.springai.exception.BizException;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.service.UserImportServiceI;
import com.example.springai.utils.PasswordGenerator;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * xlsx 批量导入用户。
 *
 * <p>逐行独立处理、逐行给结果：一个管理员一次导几十行，某一行重复/格式不对
 * 不该把其余的都带下去，而且他必须能对着 Excel 行号看出是哪几行有问题。
 * 所以这里**刻意不加 {@code @Transactional}** —— 一个大事务会让一行失败回滚整批。
 */
@Slf4j
@Service
public class UserImportService implements UserImportServiceI {

    /** 单次导入行数上限。上千行的开户需求应该走脚本，不该在浏览器里传表格。 */
    private static final int MAX_ROWS = 500;

    private static final int MAX_USERNAME_LEN = 50;   // sys_user.username varchar(50)
    private static final int MAX_REAL_NAME_LEN = 50;  // varchar(50)
    private static final int MAX_PHONE_LEN = 20;      // varchar(20)

    /**
     * 表头别名的识别顺序。
     *
     * <p>按**表头文字**定位列，不按列序 —— 管理员调整列顺序不该导致整批导入到错误字段上。
     * 每个字段给几个常见写法，是因为"用户名 / 账号 / username"在真实表格里都可能出现，
     * 认不出来会直接判成"缺少必需列"整批失败。
     *
     * <p><b>包级可见（不是 private）是刻意的</b>：下载模板用的是数组里的<b>第一个</b>写法，
     * 单测要能断言"模板里的表头，解析器自己认得出来"—— 这两边一旦漂移，
     * 管理员下载模板填好却导不进去，是最气人的那种 bug。
     */
    static final String[] H_USERNAME = {"用户名", "账号", "登录名", "username"};
    static final String[] H_REAL_NAME = {"姓名", "名字", "真实姓名", "name"};
    static final String[] H_EMAIL = {"邮箱", "邮件", "电子邮箱", "email"};
    static final String[] H_PHONE = {"手机号", "手机", "电话", "联系方式", "phone"};
    static final String[] H_DEPT = {"部门", "所属部门", "department"};

    /** 模板里的两个 sheet 名。第一个是数据页（解析器只读它），第二个是给人看的填写说明。 */
    static final String TEMPLATE_DATA_SHEET = "用户";
    static final String TEMPLATE_NOTES_SHEET = "填写说明";

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysCompanyMapper companyMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 导入账号的初始密码。
     *
     * <p><b>没有默认值，必须显式配置</b>：给一个内置的弱口令默认值，
     * 就等于所有没配过的环境都用同一个公开密码开户。
     * 没配或太短时在导入时直接报错，而不是悄悄用一个弱口令建号。
     */
    @Value("${app.user.import.default-password:}")
    private String defaultPassword;

    @Override
    public UserImportResultDTO importUsers(MultipartFile file, Long companyId) {
        UserImportResultDTO result = new UserImportResultDTO();

        // 初始密码先校验：配置错了就该立刻报出来，而不是先建 20 个账号再发现问题
        if (defaultPassword == null || defaultPassword.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "未配置导入初始密码（app.user.import.default-password），无法导入");
        }
        if (defaultPassword.length() < PasswordGenerator.MIN_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "导入初始密码太短，至少 " + PasswordGenerator.MIN_LENGTH + " 位");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要导入的 xlsx 文件");
        }
        // 公司必须真实存在：否则会建出一批 company_id 指向不存在公司的账号，
        // 它们的检索范围会退化成"仅公开文档"，而管理员完全不知道为什么搜不到东西
        SysCompany company = companyId == null ? null : companyMapper.selectById(companyId);
        if (company == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "客户公司不存在，请重新选择");
        }

        // 公司不能用的时候不给开户（A5 套餐硬拦）。做成 fatal 而不是逐行失败：
        // 整份文件都做不了，让管理员一眼看到原因，而不是对着 40 行"失败"找规律。
        // 话术面向**内部管理员**（谁会看到这个提示），所以不复用 SysCompany.blockReason
        // 里那句给客户看的"贵司..."。
        String blocked = company.blockReason(LocalDateTime.now());
        if (blocked != null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该公司当前不可用（" + blocked + "），请先恢复或续期再开户");
        }

        try (InputStream in = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "表格里没有工作表");
            }

            Row header = sheet.getRow(sheet.getFirstRowNum());
            int colUsername = findColumn(header, H_USERNAME);
            if (colUsername < 0) {
                // 必需列缺失属于整份文件不可用，直接返回 fatal 而不是逐行失败
                result.setFatal(true);
                result.setMessage("找不到「用户名」列。请在第 1 行放表头，至少包含：用户名、姓名、邮箱、手机号");
                return result;
            }
            int colRealName = findColumn(header, H_REAL_NAME);
            int colEmail = findColumn(header, H_EMAIL);
            int colPhone = findColumn(header, H_PHONE);
            int colDept = findColumn(header, H_DEPT);

            // 同一份文件内的重复必须自己拦：逐行查库时前面那行还没提交完就查不到，
            // 会一路插到唯一索引报错，用户看到的是 MySQL 的重复键信息
            Set<String> seenUsernames = new HashSet<>();
            Set<String> seenEmails = new HashSet<>();

            int last = sheet.getLastRowNum();
            int dataRows = 0;

            // 席位（A5）：上限为空 = 不限；有上限时，**成功建号的每一行占一个席位**，
            // 席位用完后剩下的行逐行判失败 —— 不整批失败，管理员要能看到
            // "哪几行没开成、为什么"，而不是一份 40 行的失败清单。
            //
            // 口径与公司管理页显示的"员工数（启用）"完全一致（同一个
            // userMapper.countActiveByCompany）：停用离职账号会释放席位，
            // 这样客户换人不用来找我们改上限。
            Integer seatLimit = company.getSeatLimit();
            long activeSeats = seatLimit == null ? 0L : userMapper.countActiveByCompany(companyId);
            int granted = 0;

            for (int r = sheet.getFirstRowNum() + 1; r <= last && dataRows < MAX_ROWS; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String username = cellString(row.getCell(colUsername));
                String realName = colRealName < 0 ? null : cellString(row.getCell(colRealName));
                String email = colEmail < 0 ? null : cellString(row.getCell(colEmail));
                String phone = colPhone < 0 ? null : cellString(row.getCell(colPhone));
                String deptName = colDept < 0 ? null : cellString(row.getCell(colDept));

                // 整行空白跳过（表格末尾常有空行），不计入总数也不报错
                if (username == null && realName == null && email == null
                        && phone == null && deptName == null) {
                    continue;
                }
                dataRows++;

                // 席位已满：这一行直接判失败，不去建号（也不消耗文件内的去重集合，
                // 所以管理员调大席位后重传整份文件，结果是一致的）
                if (seatLimit != null && activeSeats + granted >= seatLimit) {
                    result.getRows().add(new UserImportResultDTO.Row(r + 1, username, realName,
                            UserImportResultDTO.FAIL,
                            "席位已满（上限 " + seatLimit + "，当前启用 " + (activeSeats + granted)
                                    + "）：请先停用离职账号，或上调席位上限",
                            null));
                    continue;
                }

                UserImportResultDTO.Row imported = importOne(r + 1, username, realName, email,
                        phone, deptName, companyId, seenUsernames, seenEmails);
                if (UserImportResultDTO.OK.equals(imported.getState())) {
                    granted++;
                }
                result.getRows().add(imported);
            }

            if (dataRows >= MAX_ROWS) {
                result.setMessage("单次最多导入 " + MAX_ROWS + " 行，多余的已忽略");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // POI 对加密文件 / 损坏文件 / 实际不是 Office 格式的文件抛各种运行时异常
            log.warn("用户导入文件解析失败: {}", e.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST, "表格解析失败，请确认是 .xlsx 文件且未加密");
        }

        result.setTotal(result.getRows().size());
        result.setSucceeded((int) result.getRows().stream()
                .filter(x -> UserImportResultDTO.OK.equals(x.getState())).count());
        result.setFailed(result.getTotal() - result.getSucceeded());
        log.info("📥 用户批量导入完成: 公司={}, 共 {} 行, 成功 {} / 失败 {}",
                companyId, result.getTotal(), result.getSucceeded(), result.getFailed());
        return result;
    }

    /** 处理一行。任何失败都只记在该行上，不往外抛。 */
    private UserImportResultDTO.Row importOne(int rowNum, String username, String realName,
                                              String email, String phone, String deptName,
                                              Long companyId,
                                              Set<String> seenUsernames, Set<String> seenEmails) {
        UserImportResultDTO.Row row = new UserImportResultDTO.Row(
                rowNum, username == null ? "" : username, realName,
                UserImportResultDTO.FAIL, null, null);

        try {
            // ---- 校验（全部是纯输入检查，放在任何写库动作之前）----
            if (username == null) {
                row.setMessage("用户名为空");
                return row;
            }
            if (username.length() > MAX_USERNAME_LEN) {
                row.setMessage("用户名超过 " + MAX_USERNAME_LEN + " 字符");
                return row;
            }
            if (realName != null && realName.length() > MAX_REAL_NAME_LEN) {
                row.setMessage("姓名超过 " + MAX_REAL_NAME_LEN + " 字符");
                return row;
            }
            if (phone != null && phone.length() > MAX_PHONE_LEN) {
                row.setMessage("手机号超过 " + MAX_PHONE_LEN + " 字符");
                return row;
            }
            if (email != null && !EmailFormat.isValid(email)) {
                row.setMessage("邮箱格式不正确：" + email);
                return row;
            }

            String normalizedUsername = username;
            if (!seenUsernames.add(normalizedUsername.toLowerCase())) {
                row.setMessage("文件内用户名重复");
                return row;
            }
            if (existsByColumn("username", normalizedUsername)) {
                row.setMessage("用户名已存在");
                return row;
            }

            // 邮箱没填就合成一个。sys_user.email 是 NOT NULL + UNIQUE，不能留空；
            // 用 RFC 2606 保留的 .invalid 域名 —— 它永远不会被解析，
            // 一看就知道"这个账号没有真实邮箱"，比塞一个假的可达地址安全。
            String effectiveEmail;
            boolean emailSynthesized;
            if (email == null) {
                effectiveEmail = synthesizedEmail(normalizedUsername);
                emailSynthesized = true;
            } else {
                effectiveEmail = email.toLowerCase();
                emailSynthesized = false;
            }
            if (!seenEmails.add(effectiveEmail.toLowerCase())) {
                row.setMessage("文件内邮箱重复");
                return row;
            }
            if (existsByColumn("email", effectiveEmail)) {
                row.setMessage("邮箱已存在：" + effectiveEmail);
                return row;
            }

            // ---- 部门：表头照旧解析，但整个字段已不再生效 ----
            // 部门维度废弃后（见 doc/商业化方案.md「A2. 去掉部门维度」），department_id
            // 统一写常量、不参与任何权限判断。**「部门」这一列仍然解析、但既不校验也不影响
            // 该行成败** —— 原因有两条：
            //   ① 直接删掉这个表头别名，存量的导入模板会被判成"缺少必需列"整份失败；
            //   ② 以前"填了部门却在部门表里找不到"会让该行失败，而现在部门根本不影响权限，
            //      再拿它挡开户就是给用户一个与他的目标无关的失败原因。
            Long deptId = 1L;
            String deptNote = deptName == null ? null : "「部门」列已不再生效，归属部门统一为默认值";

            // ---- 建号 ----
            SysUser user = new SysUser();
            user.setUsername(normalizedUsername);
            user.setRealName(realName);
            user.setEmail(effectiveEmail);
            user.setPhone(phone);
            user.setDepartmentId(deptId);
            user.setCompanyId(companyId);
            // user_type=2：外部（客户公司员工）。这条不能漏 —— 默认值是 1（内部），
            // 漏了就把客户的人当成伯伯公司的顾问，能看所有客户的文档。
            user.setUserType(2);
            user.setIsAdmin(0);
            user.setStatus(1);
            user.setPassword(passwordEncoder.encode(defaultPassword));
            // 初始密码是配置里的**统一口令**，还给到了导入者手上。
            // 置 1 强制首次登录后改掉，否则这批账号长期共用同一个口令。
            user.setMustChangePassword(1);
            user.setCreatedAt(java.time.LocalDateTime.now());
            user.setUpdatedAt(java.time.LocalDateTime.now());
            userMapper.insert(user);

            row.setState(UserImportResultDTO.OK);
            row.setUserId(user.getId());
            List<String> notes = new ArrayList<>();
            notes.add("初始密码已设为配置值，首次登录须修改");
            if (emailSynthesized) {
                notes.add("未填邮箱，已用 " + effectiveEmail);
            }
            if (deptNote != null) {
                notes.add(deptNote);
            }
            row.setMessage(String.join("；", notes));
            return row;
        } catch (Throwable t) {
            // 捕获 Throwable：一行的任何异常（含约束冲突）都不该把整批带下去
            log.warn("用户导入单行失败 第{}行 username={}: {}", rowNum, username, t.getMessage());
            row.setState(UserImportResultDTO.FAIL);
            row.setMessage(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            return row;
        }
    }

    private boolean existsByColumn(String column, String value) {
        return userMapper.selectCount(new QueryWrapper<SysUser>().eq(column, value)) > 0;
    }

    /** 用用户名合成一个不会与真实邮箱冲突的占位地址。 */
    private static String synthesizedEmail(String username) {
        // 本地部分只保留邮箱允许的字符：中文用户名直接拼进去会通不过 EmailFormat 校验
        String local = username.replaceAll("[^a-zA-Z0-9._-]", "");
        if (local.isEmpty()) {
            // 全被过滤掉了（如纯中文用户名），用哈希兜底。username 本身唯一，
            // 所以哈希相同的概率极低；真撞了那也是这一行报"邮箱已存在"，看得见。
            local = "u" + Integer.toUnsignedString(username.hashCode(), 36);
        }
        return local + "@imported.invalid";
    }

    // ==================== 下载模板 ====================

    /**
     * 生成导入模板（xlsx 字节）。
     *
     * <p><b>表头用的是解析器认得的列名（取别名的第一个写法）</b>，两处共用同一组常量 ——
     * 管理员下载模板、填好、上传，这条链路必须闭合。模板与解析器一旦漂移，
     * 表现是"照你们给的模板填的，却说找不到用户名列"，最气人也最难解释。
     *
     * <p>两个刻意的设计：
     * <ul>
     *   <li><b>「用户」页只有表头，没有示例数据行</b>：示例行会被当成真实数据导进去
     *       （解析器跳过空行，但不会跳过"看起来像张三的示例"）。示例文字全部放在
     *       「填写说明」页里，只供人看。</li>
     *   <li>说明页是**第二个** sheet：解析器只读第一个 sheet（{@code getSheetAt(0)}），
     *       所以多出来的说明页对它完全无害。</li>
     * </ul>
     */
    @Override
    public byte[] buildTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // ---------- 第 1 页：数据（只有表头） ----------
            Sheet sheet = wb.createSheet(TEMPLATE_DATA_SHEET);
            CellStyle headerStyle = headerStyle(wb);
            Row header = sheet.createRow(0);
            String[] headers = {H_USERNAME[0], H_REAL_NAME[0], H_EMAIL[0], H_PHONE[0], H_DEPT[0]};
            int[] widths = {22, 14, 26, 18, 14};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                // 宽度用字符数 * 256（POI 的单位是 1/256 个字符宽）
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            // 冻结表头：填到几十行时还能看见列名
            sheet.createFreezePane(0, 1);

            // ---------- 第 2 页：填写说明（给人看） ----------
            Sheet notes = wb.createSheet(TEMPLATE_NOTES_SHEET);
            notes.setColumnWidth(0, 100 * 256);
            int r = 0;
            for (String[] pair : TEMPLATE_NOTES) {
                Row row = notes.createRow(r);
                Cell cell = row.createCell(0);
                cell.setCellValue(pair[0]);
                if ("H".equals(pair[1])) {
                    cell.setCellStyle(headerStyle);
                }
                r++;
            }
            // 示例行单独放，只在本页展示，不会被导入
            notes.createRow(r + 1).createCell(0)
                    .setCellValue("示例（只是给你看格式，不要复制到「用户」页的第一行以下）:");
            notes.createRow(r + 2).createCell(0)
                    .setCellValue("张三\tzhangsan\tzhangsan@example.com\t13800000000");

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            // 生成模板失败是服务端问题，不是用户输入问题 —— 抛出去让它变成 500，
            // 别伪装成"文件不对"让管理员反复重试
            throw new RuntimeException("生成导入模板失败: " + e.getMessage(), e);
        }
    }

    /** 填写说明页的内容：{文字, 是否小标题}。 */
    private static final String[][] TEMPLATE_NOTES = {
            {"用户导入模板 · 填写说明", "H"},
            {"① 在「用户」页从第 2 行开始填（第 1 行表头不要改）；② 保存为 .xlsx；"
                    + "③ 回到系统「用户管理 → 批量导入」，先选客户公司，再上传这个文件"
                    + "（本模板就是在这个弹窗里点「下载导入模板」拿到的）。", ""},
            {"必填与唯一", "H"},
            {"用户名：登录用的账号，2-50 个字符，不能与已有账号重复（必填）", ""},
            {"姓名 / 邮箱 / 手机号：可以留空。邮箱留空时系统会自动生成一个占位地址"
                    + "（用户名@imported.invalid），不影响登录", ""},
            {"列的顺序不限，表头文字能认出来就行：用户名（也可写 账号 / 登录名 / username）、"
                    + "姓名（名字 / 真实姓名）、邮箱（邮件 / 电子邮箱）、手机号（手机 / 电话 / 联系方式）", ""},
            {"注意", "H"},
            {"一次最多导入 500 行，超出的部分会被忽略", ""},
            {"初始密码由系统统一设置，导入的账号首次登录后必须修改密码才能使用", ""},
            {"「部门」这一列已不再生效：填了会被忽略、不会导致失败，习惯保留也可以", ""},
            {"不要改表头文字，不要用合并单元格；保存时请确认格式是 .xlsx（老版 .xls 请先另存为 xlsx）", ""},
            {"常见失败原因", "H"},
            {"用户名已存在 / 文件内用户名重复 / 邮箱已存在（这些行会单独失败，其余行照常导入）", ""},
            {"席位已满：该公司的可启用账号数已达上限，需要先停用离职账号或联系我们上调席位", ""},
    };

    private static CellStyle headerStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /** 在表头行里找一个列下标；找不到返回 -1。包级可见是为了单测能验证"模板自己认得出来"。 */
    static int findColumn(Row header, String[] aliases) {
        if (header == null) {
            return -1;
        }
        for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
            String text = cellString(header.getCell(c));
            if (text == null) {
                continue;
            }
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(text.trim())) {
                    return c;
                }
            }
        }
        return -1;
    }

    /**
     * 读单元格为字符串。
     *
     * <p>数字单元格要特别处理：**手机号在 Excel 里常常是数字类型**，
     * 直接 {@code toString()} 会得到 {@code 1.3812345678E10} 这种科学计数法。
     * 整数值按 long 输出，避免末尾多一个 {@code .0}。
     */
    private static String cellString(Cell cell) {
        if (cell == null) {
            return null;
        }
        switch (cell.getCellType()) {
            case STRING:
                return trimToNull(cell.getStringCellValue());
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double d = cell.getNumericCellValue();
                if (!Double.isInfinite(d) && d == Math.rint(d)) {
                    return String.valueOf((long) d);
                }
                return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                // 公式单元格取缓存结果，别把 "=A1&\"@qq.com\"" 这种字面量当值
                try {
                    return trimToNull(cell.getStringCellValue());
                } catch (IllegalStateException e) {
                    double v = cell.getNumericCellValue();
                    return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
                }
            default:
                return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
