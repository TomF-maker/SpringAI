package com.example.springai.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Excel 文本抽取（.xlsx 与 .xls 都支持）。
 *
 * <p>用 {@link WorkbookFactory#create(InputStream)} 按文件头自动选 XSSF / HSSF，
 * 所以两种格式一个入口就够。
 *
 * <p><b>输出格式是给切分器量身定的</b>：下游 {@code splitIntoChunks} 唯一的段落
 * 分隔符是字面的 {@code \n\n}。如果整张表拼成一个大字符串，它会被当成一个"段落"，
 * 超过 800 字就被每 800 字符硬切一刀 —— 一刀可能切在某行中间，把一个单元格切成两半。
 * 所以这里**行与行之间用 {@code \n\n}**，一行内的单元格用 {@code " | "} 连接，
 * 每个工作表前面插一个标题段落。这样每一行自然成为一个可检索的片段。
 */
@Slf4j
@Service
public class ExcelDocumentService implements ExcelDocumentServiceI {

    /** 行数上限：几万行的表格会直接撑爆 embedding 成本和内存。 */
    private static final int MAX_ROWS = 5000;
    /** 字符数上限，同样是成本护栏。 */
    private static final int MAX_CHARS = 200_000;

    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String extractText(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new IOException("文件名无效");
        }
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new IOException("不支持的文件格式: " + fileName);
        }

        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            String text = extract(workbook, fileName);
            log.info("📊 Excel 解析完成：{}，共 {} 字符", fileName, text.length());
            return text;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // POI 对加密文件 / 损坏文件 / 实际不是 Office 格式的文件抛的是各种运行时异常，
            // 统一转成 IOException，和 WordDocumentService 的约定保持一致
            throw new IOException("Excel 解析失败: " + e.getMessage(), e);
        }
    }

    private String extract(Workbook workbook, String fileName) {
        StringBuilder sb = new StringBuilder();
        int sheetCount = workbook.getNumberOfSheets();
        int rowCount = 0;
        boolean truncated = false;

        for (int s = 0; s < sheetCount && !truncated; s++) {
            Sheet sheet = workbook.getSheetAt(s);
            sb.append("【工作表：").append(sheet.getSheetName()).append("】\n\n");

            for (Row row : sheet) {
                if (++rowCount > MAX_ROWS) {
                    log.warn("⚠️ 表格 {} 行数超过上限 {}，已在工作表「{}」处截断",
                            fileName, MAX_ROWS, sheet.getSheetName());
                    truncated = true;
                    break;
                }
                String line = formatRow(row);
                if (line.isEmpty()) {
                    continue;
                }
                sb.append(line).append("\n\n");
                if (sb.length() > MAX_CHARS) {
                    log.warn("⚠️ 表格 {} 文本超过 {} 字符，已在工作表「{}」处截断",
                            fileName, MAX_CHARS, sheet.getSheetName());
                    truncated = true;
                    break;
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 把一行拼成 {@code 值1 | 值2 | 值3}。
     *
     * <p>中间的空白单元格保留为空段（{@code a |  | c}），这样列的位置关系不会错乱 ——
     * 对"第 2 列是空的"这种信息，位置比省几个字符重要。
     */
    private String formatRow(Row row) {
        int lastCell = row.getLastCellNum();
        if (lastCell <= 0) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < lastCell; c++) {
            if (c > 0) {
                line.append(" | ");
            }
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            line.append(cellValue(cell));
        }
        return line.toString().trim();
    }

    private String cellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        try {
            CellType type = cell.getCellType();
            // 公式单元格取的是缓存的结算结果，而不是公式本身
            if (type == CellType.FORMULA) {
                type = cell.getCachedFormulaResultType();
            }
            switch (type) {
                case STRING:
                    return cell.getStringCellValue().trim();
                case BOOLEAN:
                    return String.valueOf(cell.getBooleanCellValue());
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return formatDate(cell.getLocalDateTimeCellValue());
                    }
                    return formatNumber(cell.getNumericCellValue());
                default:
                    return "";
            }
        } catch (Exception e) {
            log.debug("单元格取值失败，按空处理: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 日期单元格必须特判 —— POI 默认返回的是 <b>Excel 序列号</b>，
     * 不判断的话 2026-09-14 会变成 46265 这种数字。表格类文档日期特别多，
     * 这是 Excel 抽取里最经典的坑。
     */
    private String formatDate(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        boolean midnight = value.getHour() == 0 && value.getMinute() == 0 && value.getSecond() == 0;
        return midnight ? DATE_ONLY.format(value) : DATE_TIME.format(value);
    }

    /** 整数不要显示成 10.0。 */
    private String formatNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
