package com.example.springai.controller;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/qdrant")
public class QdrantClearController {

    private final RestTemplate restTemplate;
    private final String qdrantBaseUrl;
    private final String collectionName;

    public QdrantClearController(
            @Value("${spring.ai.vectorstore.qdrant.host:124.221.251.183}") String host,
            @Value("${qdrant.http.port:6333}") int httpPort,
            @Value("${spring.ai.vectorstore.qdrant.collection-name:purchase_docs}") String collectionName) {
        this.restTemplate = new RestTemplate();
        this.qdrantBaseUrl = "http://" + host + ":" + httpPort;
        this.collectionName = collectionName;
        log.info("🔧 QdrantClearController 初始化完成，基础URL: {}, 集合: {}", qdrantBaseUrl, collectionName);
    }

    /**
     * 清空集合中的所有点（使用 REST API）
     * DELETE /api/qdrant/clear?confirm=true
     */
    @DeleteMapping("/clear")
    public Map<String, Object> clearCollection(@RequestParam(defaultValue = "false") boolean confirm) {
        Map<String, Object> response = new HashMap<>();
        if (!confirm) {
            response.put("success", false);
            response.put("message", "⚠️ 请确认操作：使用 ?confirm=true");
            return response;
        }

        try {
            // 1. 检查集合是否存在
            String infoUrl = qdrantBaseUrl + "/collections/" + collectionName;
            ResponseEntity<Map> infoResponse = restTemplate.getForEntity(infoUrl, Map.class);
            if (infoResponse.getStatusCode() != HttpStatus.OK) {
                response.put("success", false);
                response.put("message", "集合不存在或无法访问");
                return response;
            }

            // 获取当前点数（用于日志）
            Map infoBody = infoResponse.getBody();
            Number pointsCount = null;
            if (infoBody != null && infoBody.containsKey("result")) {
                Map result = (Map) infoBody.get("result");
                if (result.containsKey("points_count")) {
                    pointsCount = (Number) result.get("points_count");
                }
            }
            log.info("📊 当前集合 {} 中有 {} 个点", collectionName, pointsCount != null ? pointsCount : "未知");

            // 2. 构建删除请求体（filter 设为 null，表示删除所有点）
            Map<String, Object> deleteBody = new HashMap<>();
            // 注意：Qdrant REST API 中，filter 为 null 或不传，都表示匹配所有
            // 这里显式设为 null 更明确
            deleteBody.put("filter", new HashMap<>());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(deleteBody, headers);

            String deleteUrl = qdrantBaseUrl + "/collections/" + collectionName + "/points/delete";
            ResponseEntity<Map> deleteResponse = restTemplate.exchange(
                    deleteUrl,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );

            if (deleteResponse.getStatusCode() == HttpStatus.OK) {
                Map deleteResult = deleteResponse.getBody();
                if (deleteResult != null && deleteResult.containsKey("result")) {
                    log.info("✅ 清空成功，删除了 {} 个点", pointsCount != null ? pointsCount : "未知");
                    response.put("success", true);
                    response.put("message", "✅ 清空成功");
                    response.put("deletedCount", pointsCount != null ? pointsCount.intValue() : -1);
                } else {
                    response.put("success", false);
                    response.put("message", "清空操作失败，响应：" + deleteResult);
                }
            } else {
                response.put("success", false);
                response.put("message", "清空请求失败，状态码：" + deleteResponse.getStatusCode());
            }
        } catch (Exception e) {
            log.error("❌ 清空失败", e);
            response.put("success", false);
            response.put("message", "清空失败: " + e.getMessage());
        }
        return response;
    }

    /**
     * 获取集合信息
     * GET /api/qdrant/info
     */
    @GetMapping("/info")
    public Map<String, Object> getCollectionInfo() {
        Map<String, Object> response = new HashMap<>();
        try {
            String url = qdrantBaseUrl + "/collections/" + collectionName;
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            if (resp.getStatusCode() == HttpStatus.OK) {
                response.put("success", true);
                response.put("collectionName", collectionName);
                response.put("info", resp.getBody());
            } else {
                response.put("success", false);
                response.put("message", "获取信息失败，状态码：" + resp.getStatusCode());
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return response;
    }
}