package com.example.springai.service.impl;

import com.example.springai.common.ErrorCode;
import com.example.springai.dto.BatchUploadStatusDTO;
import com.example.springai.dto.DocumentUploadDTO;
import com.example.springai.entity.KbDocument;
import com.example.springai.exception.BizException;
import com.example.springai.service.BatchUploadServiceI;
import com.example.springai.service.DocumentServiceI;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 批量入库实现。
 *
 * <p><b>状态存在内存里</b>，不是 Redis / MySQL。前提是单实例部署（当前就是）。
 * 这样选是因为：批次是短生命周期任务（几分钟），进程重启时那一批本来也断了，
 * 持久化到库里反而要处理"上次没跑完的批次怎么恢复"这个没人需要回答的问题。
 * <b>改成多实例部署时必须换掉</b> —— 提交和轮询会落到不同实例上，各自看到的状态不一样。
 */
@Slf4j
@Service
public class BatchUploadService implements BatchUploadServiceI {

    /** 单批文件数上限。再多应该用扫目录的脚本，而不是让人在浏览器里选。 */
    private static final int MAX_BATCH_FILES = 100;

    @Autowired
    private DocumentServiceI documentService;

    /**
     * 进行中的批次；null 表示空闲。
     *
     * <p>用 AtomicReference 而不是普通字段配 synchronized：submit 需要把
     * "检查是否空闲"和"占位"两步做成一个原子操作，CAS 一句话就能表达。
     * 先 get 判空再 set 的写法在并发下有两个请求都看到空闲、然后都提交的可能。
     */
    private final AtomicReference<BatchJob> running = new AtomicReference<>();

    /** 最近一次完成的批次，供前端刷新后查看结果。 */
    private volatile BatchJob lastFinished;

    private ThreadPoolTaskExecutor executor;

    @PostConstruct
    void init() {
        // **单线程**：整批串行处理。理由见类注释的"只允许一个批次"。
        // 不要在单个任务内再叠并发 —— processDocument 自己已经按分片并行了，
        // 外层再并发只会把 CPU 抢成两拨互相等。
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setQueueCapacity(1);
        taskExecutor.setThreadNamePrefix("doc-batch-");
        taskExecutor.initialize();
        this.executor = taskExecutor;
        log.info("🔧 批量入库线程池初始化完成（单线程串行）");
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public String submit(List<MultipartFile> files, DocumentUploadDTO meta, Long userId) {
        if (files == null || files.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
        if (files.size() > MAX_BATCH_FILES) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单次最多上传 " + MAX_BATCH_FILES + " 份文件，当前 " + files.size() + " 份");
        }

        BatchJob job = new BatchJob(files, meta, userId);

        // 先占位，再落盘。顺序不能反：占位失败时（已有批次在跑）还没产生任何临时文件，
        // 也就不需要清理 —— 反过来写就必须记得清理，漏一次就是一堆垃圾文件。
        if (!running.compareAndSet(null, job)) {
            throw new BizException(ErrorCode.CONFLICT, "已有批量任务正在执行，请等它完成后再提交");
        }

        try {
            saveToTempFiles(job);
            executor.execute(() -> run(job));
        } catch (Throwable t) {
            // 落盘失败或线程池拒绝：必须把占位还原，否则系统永久卡在"忙"状态，
            // 而且不会有任何报错 —— 用户只会看到"已有批量任务正在执行"却永远等不到结束。
            running.set(null);
            deleteTempFiles(job);
            if (t instanceof BizException be) {
                throw be;
            }
            log.error("❌ 批量任务提交失败", t);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "批量任务提交失败: " + t.getMessage());
        }

        log.info("📦 批量入库已受理: batchId={}, {} 份文件, 上传人={}", job.batchId, job.items.size(), userId);
        return job.batchId;
    }

    @Override
    public BatchUploadStatusDTO current() {
        BatchJob job = running.get();
        if (job == null) {
            job = lastFinished;
        }
        return job == null ? null : snapshot(job);
    }

    @Override
    public BatchUploadStatusDTO get(String batchId) {
        if (batchId == null) {
            return null;
        }
        BatchJob job = running.get();
        if (job != null && job.batchId.equals(batchId)) {
            return snapshot(job);
        }
        BatchJob done = lastFinished;
        if (done != null && done.batchId.equals(batchId)) {
            return snapshot(done);
        }
        return null;
    }

    @Override
    public boolean isBusy() {
        return running.get() != null;
    }

    // ==================== 后台执行 ====================

    /**
     * 串行处理整批。
     *
     * <p>单份失败<b>不中断</b>整批：继续处理剩下的，最后一起汇总。
     * 批量导入时最怕的就是"第 3 份有问题，后面 20 份白等了"。
     */
    private void run(BatchJob job) {
        try {
            for (ItemState item : job.items) {
                synchronized (job) {
                    item.state = BatchUploadStatusDTO.UPLOADING;
                }
                processOne(job, item);
            }
        } finally {
            // 无论中途发生什么都要收尾：标结束、释放占位、留存最近批次。
            // 少了 running.set(null) 系统就永久卡在"忙"。
            synchronized (job) {
                job.finishedAt = System.currentTimeMillis();
            }
            running.set(null);
            lastFinished = job;
            BatchUploadStatusDTO s = snapshot(job);
            log.info("📦 批量入库结束: batchId={}, 共 {} 份, 成功 {} / 失败 {}",
                    job.batchId, s.getTotal(), s.getSucceeded(), s.getFailed());
        }
    }

    private void processOne(BatchJob job, ItemState item) {
        Path temp = item.file.tempPath;
        try {
            byte[] content = Files.readAllBytes(temp);
            // 复用单份上传的完整链路：校验、写库、写 Qdrant、状态标记都在里面。
            KbDocument doc = documentService.uploadDocumentBytes(
                    content, item.file.originalName, job.meta, job.userId);
            synchronized (job) {
                item.state = BatchUploadStatusDTO.OK;
                item.message = "已切分 " + doc.getChunkCount() + " 个片段";
                item.documentId = doc.getId();
            }
        } catch (Throwable t) {
            // 捕获 Throwable：一份文档的解析失败（甚至 OOM）不该把整批带下去
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            synchronized (job) {
                item.state = BatchUploadStatusDTO.FAIL;
                item.message = msg;
            }
            log.warn("批量入库单份失败 batchId={} file={}: {}", job.batchId, item.file.originalName, msg);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                // 临时文件删不掉只影响磁盘，别影响批次结果
                log.debug("临时文件清理失败: {}", temp);
            }
        }
    }

    // ==================== 临时文件 ====================

    /**
     * 把 MultipartFile 落到临时文件。
     *
     * <p><b>必须在请求线程上做</b>：MultipartFile 绑定在 Servlet 请求上，
     * 请求一返回就读不出来了。落盘而不是留在内存里，是因为一批可能有几十份、
     * 每份最大 50MB —— 全留在内存会直接把堆撑爆。落盘之后后台线程逐份读，
     * 内存峰值就只有一份文件的大小。
     */
    private void saveToTempFiles(BatchJob job) throws IOException {
        for (ItemState item : job.items) {
            Path temp = Files.createTempFile("kb-batch-", ".tmp");
            try {
                item.file.multipart.transferTo(temp);
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            item.file.tempPath = temp;
        }
    }

    private void deleteTempFiles(BatchJob job) {
        for (ItemState item : job.items) {
            if (item.file.tempPath != null) {
                try {
                    Files.deleteIfExists(item.file.tempPath);
                } catch (IOException ignored) {
                    // 已经尽力了，这是失败路径上的清理
                }
            }
        }
    }

    // ==================== 快照 ====================

    /**
     * 拷一份当前状态出去。
     *
     * <p>在 job 上加锁拷：后台线程正在改 item，不锁的话可能序列化出
     * "总数 10、已完成 3、成功 2、失败 0" 这种自相矛盾的数（第 3 个刚好改到一半）。
     */
    private BatchUploadStatusDTO snapshot(BatchJob job) {
        BatchUploadStatusDTO dto = new BatchUploadStatusDTO();
        List<BatchUploadStatusDTO.Item> items = new ArrayList<>();
        int ok = 0;
        int fail = 0;
        synchronized (job) {
            for (ItemState s : job.items) {
                if (BatchUploadStatusDTO.OK.equals(s.state)) {
                    ok++;
                } else if (BatchUploadStatusDTO.FAIL.equals(s.state)) {
                    fail++;
                }
                items.add(new BatchUploadStatusDTO.Item(
                        s.file.originalName, s.state, s.message, s.documentId));
            }
            dto.setBatchId(job.batchId);
            dto.setState(job.finishedAt > 0 ? BatchUploadStatusDTO.DONE : BatchUploadStatusDTO.RUNNING);
            dto.setTotal(job.items.size());
            dto.setSucceeded(ok);
            dto.setFailed(fail);
            dto.setFinished(ok + fail);
            dto.setStartedAt(formatTime(job.startedAt));
            dto.setFinishedAt(job.finishedAt > 0 ? formatTime(job.finishedAt) : null);
        }
        dto.setItems(items);
        return dto;
    }

    private static String formatTime(long epochMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(epochMillis));
    }

    // ==================== 内部结构 ====================

    /** 一批任务。 */
    private static class BatchJob {
        final String batchId = UUID.randomUUID().toString();
        final List<ItemState> items = new ArrayList<>();
        final DocumentUploadDTO meta;
        final Long userId;
        final long startedAt = System.currentTimeMillis();
        /** 0 表示还没结束。 */
        long finishedAt;

        BatchJob(List<MultipartFile> files, DocumentUploadDTO meta, Long userId) {
            this.meta = meta;
            this.userId = userId;
            for (MultipartFile f : files) {
                String name = f.getOriginalFilename();
                items.add(new ItemState(new PendingFile(name == null ? "未命名文件" : name, f)));
            }
        }
    }

    /** 一份待处理的文件：原始名字 + 还在请求上的 MultipartFile + 落盘后的临时路径。 */
    private static class PendingFile {
        final String originalName;
        final MultipartFile multipart;
        Path tempPath;

        PendingFile(String originalName, MultipartFile multipart) {
            this.originalName = originalName;
            this.multipart = multipart;
        }
    }

    /** 一份文件的处理状态，由后台线程改写、被轮询读走。 */
    private static class ItemState {
        final PendingFile file;
        String state = BatchUploadStatusDTO.WAITING;
        String message;
        Long documentId;

        ItemState(PendingFile file) {
            this.file = file;
        }
    }
}
