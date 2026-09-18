package com.example.springai.service;

import com.example.springai.dto.BatchUploadStatusDTO;
import com.example.springai.dto.DocumentUploadDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 批量入库（异步）。
 *
 * <p><b>为什么是异步</b>：每份文档都要跑 embedding，一份几秒到几十秒，
 * 一批几十份就是好几分钟。同步接口会把浏览器一直挂着，中间任何一步超时
 * （网关、浏览器、代理）都会让整批结果不可知 —— 而服务端其实还在跑。
 * 所以提交立刻返回批次 id，前端轮询进度。
 *
 * <p><b>同一时间只允许一个批次</b>：几批文件同时跑会抢同一份 embedding 资源，
 * 把 2 核机器拖垮；进度也会混在一起分不清归属。
 * 这条约束在服务端强制（CAS 占位），不是靠前端禁用按钮 —— 刷新页面就绕过了。
 */
public interface BatchUploadServiceI {

    /**
     * 提交一批文件，立即返回批次 id。
     *
     * <p>本方法在<b>请求线程</b>上执行：会先把 MultipartFile 落到临时文件
     * （异步任务开始跑时请求早已结束，MultipartFile 已失效），再交给后台线程处理。
     *
     * @param files 一次选择的全部文件，按传入顺序处理
     * @param meta  整批共用的元数据。<b>客户管理员的收口必须由调用方在此之前完成</b>
     *              （见 DocumentController#scopeUploadForClientAdmin）——
     *              这里刻意不做，避免收口逻辑出现第二份实现
     * @param userId 上传人
     * @return 批次 id
     * @throws com.example.springai.exception.BizException 已有批次在执行中（CONFLICT），
     *                                                     或文件数与大小不合规（BAD_REQUEST）
     */
    String submit(List<MultipartFile> files, DocumentUploadDTO meta, Long userId);

    /**
     * 当前进行中的批次；没有则返回最近一次完成的。
     *
     * <p>页面刷新后靠它恢复进度显示 —— 只查进行中的那批，已完成的不再重复弹提示。
     *
     * @return 从未提交过任何批次时返回 null
     */
    BatchUploadStatusDTO current();

    /**
     * 按 id 查批次状态（前端轮询用）。
     *
     * @return 该批次已完成且已被新批次顶替、或 id 不存在时返回 null
     */
    BatchUploadStatusDTO get(String batchId);

    /**
     * 是否有批次正在执行。
     *
     * <p>给<b>同步导入接口</b>（单份上传、URL 上传）做前置判断用：那些接口也要跑
     * embedding，跟批次并发会互相抢 CPU，2 核机器上尤其明显。
     * 前端已经禁用了入口，但那只是提示 —— 刷新页面、或直接敲接口都能绕过，
     * 约束必须在服务端。
     */
    boolean isBusy();
}
