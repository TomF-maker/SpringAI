package com.example.springai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 一个批量入库任务的状态快照。
 *
 * <p>前端靠轮询它来显示进度、判断是否结束，所以这是个<b>只读快照</b> ——
 * 后台线程在改真实任务对象，这个 DTO 是拷出来的一份，不再变化。
 * 直接把可变的任务对象丢给 Jackson 序列化会和后台线程的写入打架
 * （可能序列化到一半状态就变了，出现"总数 10 但已完成 3、成功 2、失败 0"这种对不上的数）。
 */
@Data
public class BatchUploadStatusDTO {

    /** 批次 id，前端轮询用。 */
    private String batchId;

    /** {@link #RUNNING} 或 {@link #DONE}。 */
    private String state;

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";

    /** 本批文件总数。 */
    private int total;
    /** 已处理完的份数（成功 + 失败）。 */
    private int finished;
    private int succeeded;
    private int failed;

    /** 开始 / 结束时间，展示用。 */
    private String startedAt;
    /** 未结束时为 null。 */
    private String finishedAt;

    private List<Item> items = Collections.emptyList();

    /** 单份文件的状态。 */
    @Data
    @AllArgsConstructor
    public static class Item {

        /** 原始文件名。不展示临时文件路径，也不含 UUID 前缀。 */
        private String fileName;

        /** {@link #WAITING} / {@link #UPLOADING} / {@link #OK} / {@link #FAIL}。 */
        private String state;

        /** 成功时是"已切分 N 个片段"，失败时是失败原因。 */
        private String message;

        /** 成功入库后的文档 id；未成功为 null。 */
        private Long documentId;
    }

    public static final String WAITING = "WAITING";
    public static final String UPLOADING = "UPLOADING";
    public static final String OK = "OK";
    public static final String FAIL = "FAIL";
}
