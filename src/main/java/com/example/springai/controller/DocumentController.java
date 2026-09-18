package com.example.springai.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.common.ErrorCode;
import com.example.springai.common.PageResult;
import com.example.springai.common.Response;
import com.example.springai.dto.BatchUploadStatusDTO;
import com.example.springai.dto.DocumentListDTO;
import com.example.springai.dto.DocumentUploadDTO;
import com.example.springai.dto.UrlUploadRequest;
import com.example.springai.entity.KbDocument;
import com.example.springai.entity.SysCompany;
import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.service.BatchUploadServiceI;
import com.example.springai.service.DocumentServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理（上传 / 列表 / 删除 / 下载）—— <b>内部管理员 + 客户公司管理员</b>。
 *
 * <p>类级 {@code @PreAuthorize} 而不是逐个方法标：漏标一个方法就是一个洞。
 * 这里放开给 {@code CLIENT_ADMIN}（客户公司自己的管理员），是因为客户需要
 * 管理本公司的资料；但<b>客户管理员的作用域必须被收口到本公司</b>，
 * 否则他就成了"能改别家知识库"的越权账号。
 *
 * <h3>客户管理员的收口规则（改这个方法前先读）</h3>
 * <ul>
 *   <li><b>上传</b>：{@code clientId} 强制改写成本公司（请求里传什么都无效）、
 *       {@code isPublic} 强制 false。后者不能省 —— 检索侧的公开分支是宽松的
 *       {@code is_public == 1}（为兼容存量数据），客户自己把文档标成公开，
 *       就等于让<b>其他客户</b>也能搜到它。</li>
 *   <li><b>列表</b>：service 的外部分支本来就按 {@code company_id} 过滤，
 *       且完全忽略传入的 {@code clientId} 参数，所以传什么都越不过去。</li>
 *   <li><b>删除</b>：只能删本公司的专属文档（见 {@code canDeleteDocument}），
 *       不能删通用文档。</li>
 *   <li><b>详情</b>：必须走 {@code getDocumentForUser}（带可见性校验），
 *       <b>不能用裸的 getDocumentById</b>，否则改 URL 的 id 就能读到别家的文档元数据。</li>
 *   <li><b>客户公司下拉</b> {@code /clients}：只返回本公司 —— 完整客户名单是
 *       伯伯公司的商业资产，不能给客户看。</li>
 * </ul>
 *
 * <p><b>菜单里的 admin-only 不是访问控制</b>，只是个视觉提示：不隐藏菜单时
 * 用户点进来会看到一片 403，而隐藏了菜单他们仍然可以直接敲 URL 调接口。
 * 真正的门在这里和 service 层。
 */
@RestController
@RequestMapping("/api/documents")
@PreAuthorize("hasAnyRole('ADMIN','CLIENT_ADMIN')")
@Slf4j
public class DocumentController {

    @Autowired
    private DocumentServiceI documentService;

    @Autowired
    private UserServiceI userService;

    @Autowired
    private SysCompanyMapper companyMapper;

    @Autowired
    private BatchUploadServiceI batchUploadService;

    // ==================== 原有上传接口（兼容） ====================
    /**
     * 无元数据上传（兼容旧调用）。
     *
     * <p>这个方法写进去的文档<b>没有 clientId</b>，也就是"通用方法论"——
     * 所有客户都能检索到。所以它<b>必须继续只给内部管理员</b>：
     * 一旦客户管理员能调，他就能往通用池里灌内容，直接污染其他客户的知识库。
     * 方法级注解比类级更严，Spring 两个注解都生效（取交集）。
     */
    @PostMapping("/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<Map<String, Object>> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (batchUploadService.isBusy()) {
            return Response.fail(ErrorCode.CONFLICT, "有批量任务正在入库，请等它完成后再上传");
        }
        if (file.isEmpty()) {
            return Response.fail(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        String fileName = file.getOriginalFilename();
        // 与 DocumentServiceImpl.isSupportedFileType 的白名单保持一致。
        // 此前这里只放行 pdf/doc/docx，比 service 层还窄，导致 txt/md/xlsx 走这个接口会被拒。
        if (fileName == null || !isSupportedExtension(fileName)) {
            return Response.fail(ErrorCode.BAD_REQUEST,
                    "仅支持 PDF、DOC、DOCX、XLS、XLSX、TXT、MD 格式文件");
        }
        try {
            int chunkCount = documentService.processDocument(file);
            Map<String, Object> data = new HashMap<>();
            data.put("fileName", fileName);
            data.put("chunkCount", chunkCount);
            return Response.success(data);
        } catch (IOException e) {
            log.error("文档处理失败", e);
            return Response.fail(ErrorCode.INTERNAL_ERROR, "文档处理失败: " + e.getMessage());
        }
    }

    // ==================== 带元数据上传（含权限） ====================
    /**
     * @param departmentId 归属部门。**已废弃**：部门维度去掉了（见 doc/商业化方案.md
     *                     「A2. 去掉部门维度」），这里仍然收下这个参数只是为了让
     *                     既有调用方（前端旧页面、doc/ 下的入库脚本）不用改就能继续传 ——
     *                     它的值不会被使用，落库的 {@code department_id} 一律是常量
     *                     （{@code DocumentServiceImpl.resolveDepartmentId}）。
     * @param visibleType  可见性类型（1=本部门 2=全公司 3=指定部门）。**同样已废弃**：
     *                     现在决定可见范围的只有 {@code clientId} + {@code isPublic} 两档，
     *                     收下它只是为了填满 NOT NULL 列、让旧脚本不用改。新前端不再传这个值。
     */
    @PostMapping("/upload/metadata")
    public Response<KbDocument> uploadDocumentWithMetadata(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(defaultValue = "1") Integer visibleType,
            @RequestParam(defaultValue = "false") Boolean isPublic,
            Authentication authentication) {

        SysUser user = currentUser(authentication);
        // 批次执行中不再接受同步导入：两者都要跑 embedding，并发会互相抢 CPU。
        // 前端已经禁用了入口，但那只在页面里有效 —— 这里才是真正的约束。
        if (batchUploadService.isBusy()) {
            return Response.fail(ErrorCode.CONFLICT, "有批量任务正在入库，请等它完成后再上传");
        }

        DocumentUploadDTO meta = new DocumentUploadDTO();
        meta.setTitle(title);
        meta.setDepartmentId(departmentId);
        meta.setClientId(clientId);
        meta.setVisibleType(visibleType);
        meta.setIsPublic(isPublic);
        scopeUploadForClientAdmin(user, meta);

        try {
            return Response.success(documentService.uploadDocument(file, meta, user.getId()));
        } catch (Exception e) {
            log.error("上传失败", e);
            return Response.fail(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    // ==================== 从 URL 上传 ====================
    @PostMapping("/upload/url")
    public Response<KbDocument> uploadFromUrl(@RequestBody UrlUploadRequest request,
                                              Authentication authentication) {
        SysUser user = currentUser(authentication);
        if (batchUploadService.isBusy()) {
            return Response.fail(ErrorCode.CONFLICT, "有批量任务正在入库，请等它完成后再上传");
        }
        DocumentUploadDTO meta = new DocumentUploadDTO();
        meta.setTitle(request.getTitle());
        meta.setDepartmentId(request.getDepartmentId());
        meta.setVisibleType(request.getVisibleType() != null ? request.getVisibleType() : 1);
        meta.setIsPublic(request.getIsPublic() != null ? request.getIsPublic() : false);
        meta.setClientId(request.getClientId());
        scopeUploadForClientAdmin(user, meta);

        try {
            return Response.success(
                    documentService.uploadFromUrl(request.getUrl(), meta, user.getId()));
        } catch (Exception e) {
            log.error("URL上传失败", e);
            return Response.fail(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    // ==================== 批量入库（异步） ====================
    /**
     * 提交一批文件，<b>立即返回批次 id</b>，实际入库在后台串行执行。
     *
     * <p>为什么不像单份上传那样同步等：每份都要跑 embedding，一批几十份就是好几分钟，
     * 浏览器会一直挂着，中间任何一层超时（网关 / 浏览器 / 代理）都会让整批结果不可知
     * —— 而服务端其实还在跑。异步之后前端只需轮询进度。
     *
     * <p>客户管理员的收口在这里做（复用单份上传的同一个
     * {@link #scopeUploadForClientAdmin}），之后的异步任务只管用这份已收口的元数据 ——
     * 收口只能有一份实现，否则迟早分叉出一条漏洞。
     *
     * <p><b>不要在这个方法外面套 catch (Exception)</b>：那会把
     * "已有批次在执行中"（CONFLICT）伪装成"上传失败"，用户就不知道等一会儿就好。
     */
    @PostMapping("/upload/batch")
    public Response<Map<String, Object>> uploadBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(defaultValue = "1") Integer visibleType,
            @RequestParam(defaultValue = "false") Boolean isPublic,
            Authentication authentication) {

        SysUser user = currentUser(authentication);

        DocumentUploadDTO meta = new DocumentUploadDTO();
        meta.setTitle(title);
        meta.setDepartmentId(departmentId);
        meta.setClientId(clientId);
        meta.setVisibleType(visibleType);
        meta.setIsPublic(isPublic);
        scopeUploadForClientAdmin(user, meta);

        String batchId = batchUploadService.submit(files, meta, user.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("batchId", batchId);
        data.put("total", files.size());
        return Response.success(data);
    }

    /**
     * 当前批次（进行中的；没有则返回最近一次完成的）。
     *
     * <p>页面刷新后靠它恢复进度显示。从未提交过任何批次时 data 为 null。
     */
    @GetMapping("/upload/batch/current")
    public Response<BatchUploadStatusDTO> currentBatch() {
        return Response.success(batchUploadService.current());
    }

    /**
     * 轮询批次进度。
     *
     * <p>该批次已完成且被新批次顶替、或 id 不存在时 data 为 null ——
     * 前端据此停止轮询即可，不必当错误处理。
     */
    @GetMapping("/upload/batch/{batchId}")
    public Response<BatchUploadStatusDTO> batchStatus(@PathVariable String batchId) {
        return Response.success(batchUploadService.get(batchId));
    }

    // ==================== 文档列表 ====================
    @GetMapping("/list")
    public Response<PageResult<DocumentListDTO>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long clientId,
            Authentication authentication) {

        SysUser user = currentUser(authentication);
        // 客户管理员只能看本公司的：service 的外部分支按 companyId 过滤，
        // 且**完全忽略传入的 clientId**。这里显式置空只是让"参数无作用"这件事一眼可见，
        // 不依赖读 service 才发现。
        Long effectiveClientId = user.isClientAdmin() ? null : clientId;
        Page<DocumentListDTO> p =
                documentService.listDocuments(page, size, keyword, effectiveClientId, user.getId());
        return Response.success(PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }

    // ==================== 删除文档 ====================
    @DeleteMapping("/{id}")
    public Response<Void> deleteDocument(@PathVariable Long id, Authentication authentication) {
        SysUser user = currentUser(authentication);
        documentService.deleteDocument(id, user.getId());
        return Response.success();
    }

    // ==================== 下载文档 ====================
    // 【注意】这个接口返回二进制流，绝对不能用 Response 包裹：
    // 客户端拿它当文件下载（octet-stream + Content-Disposition），
    // 包成 JSON 信封会让下载直接坏掉。
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id,
                                                     Authentication authentication) {
        SysUser user = currentUser(authentication);

        Resource resource = documentService.downloadDocument(id, user.getId());
        KbDocument doc = documentService.getDocumentById(id);

        String fileName = doc.getFileName();
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .body(resource);
    }

    // ==================== 获取文档详情 ====================
    // 【必须走带校验的 getDocumentForUser】放开给客户管理员之后，裸的
    // getDocumentById 会让"改一下 URL 里的 id"变成读别家文档元数据的入口。
    @GetMapping("/{id}")
    public Response<KbDocument> getDocument(@PathVariable Long id, Authentication authentication) {
        SysUser user = currentUser(authentication);
        return Response.success(documentService.getDocumentForUser(id, user.getId()));
    }

    // ==================== 客户公司列表（上传表单下拉用） ====================
    // 内部管理员：返回所有客户公司，供选择「所属客户」。
    // **客户管理员：只返回本公司** —— 完整客户名单是伯伯公司的商业资产，
    // 给客户看到"我们还在给哪几家做咨询"是不该发生的。
    // 不分页 —— 客户数 10~50 家，一次全拿过来下拉刚好。
    @GetMapping("/clients")
    public Response<List<Map<String, Object>>> listClients(Authentication authentication) {
        SysUser user = currentUser(authentication);

        List<SysCompany> companies;
        if (user.isClientAdmin()) {
            SysCompany own = user.getCompanyId() == null ? null : companyMapper.selectById(user.getCompanyId());
            companies = own == null ? List.of() : List.of(own);
        } else {
            companies = companyMapper.selectList(null);
        }

        List<Map<String, Object>> list = companies.stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("companyName", c.getCompanyName());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
        return Response.success(list);
    }

    /**
     * 把客户管理员的上传元数据收口到本公司。
     *
     * <p>两条都不能少：
     * <ul>
     *   <li><b>clientId 强制成本公司</b>：否则他在请求里带上别家的 id，
     *       就能把内容塞进别家的知识库（或塞成通用文档污染所有人）。</li>
     *   <li><b>isPublic 强制 false</b>：检索侧的公开分支是宽松的 {@code is_public == 1}
     *       （兼容存量数据的过渡写法），客户把文档标成公开 = 让别家客户也能搜到。</li>
     * </ul>
     *
     * <p>原先还有一条"departmentId 改写成本人部门"，随部门维度一起去掉了
     * （归属部门现在统一是常量，本来也不需要改写）。
     *
     * <p>内部管理员不受影响，仍可按需指定客户与公开性。
     */
    private void scopeUploadForClientAdmin(SysUser user, DocumentUploadDTO meta) {
        if (!user.isClientAdmin()) {
            return;
        }
        meta.setClientId(user.getCompanyId());
        meta.setIsPublic(false);
    }

    /** 与 service 层保持一致的白名单，避免两个地方各写一份、时间一长就对不上。 */
    private boolean isSupportedExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                || lower.endsWith(".txt") || lower.endsWith(".md");
    }

    private SysUser currentUser(Authentication authentication) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }
}
