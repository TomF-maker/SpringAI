package com.example.springai.common;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页载荷，配合 {@code Response<PageResult<T>>} 使用。
 *
 * <p>为什么不把 {@code total/page/size} 直接挂到 {@link Response} 上：
 * 那样会让这三个字段在另外三十多个非分页接口上永远无意义；更关键的是
 * <b>分页改到一半是静默失败</b> —— 列表取对了、页数没取对的话，
 * 表格会正常渲染当前页，只有翻到第 2 页才会发现控件塌了。
 * 放进 data 里，{@code data.data} 就统一是"这个页面要渲染的东西"。
 *
 * <p>也刻意不复用 MyBatis-Plus 的 {@code Page<T>}：它会连带把
 * {@code orders}、{@code optimizeCountSql}、{@code searchCount}、{@code countId}
 * 等内部字段一起序列化出去，把线协议绑死在持久层实现上。
 */
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<T> records;
    private long total;
    private long page;
    private long size;

    public PageResult() {
    }

    public PageResult(List<T> records, long total, long page, long size) {
        this.records = records == null ? Collections.emptyList() : records;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        return new PageResult<>(records, total, page, size);
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
