package com.example.springai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.tool.ToolExecutor;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagServiceImpl implements RagServiceI {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private LocalKnowledgeServiceI localKnowledgeService;   // 注入本地知识库

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private QdrantClient qdrantClient;
    @Autowired
    private EmbeddingModel embeddingModel;
    @Value("${spring.ai.vectorstore.qdrant.collection-name:purchase_docs}")
    private String collectionName;
    /**
     * 阻塞式 RAG 问答
     */
    @Override
    public String chatWithDocument(String question) {
        log.info("🔍 收到RAG问答请求: {}", question);
        long startTime = System.currentTimeMillis();

        // 优先匹配本地知识库
        String localAnswer = localKnowledgeService.match(question);
        if (localAnswer != null) {
            log.info("✅ 本地知识库命中，直接返回");
            return localAnswer;
        }

        // 1. 检索相关文档片段
        Long userId = getCurrentUserId();
        List<Document> relevantDocs = retrieveDocuments(question, userId);

        if (relevantDocs.isEmpty()) {
            return "抱歉，在知识库中未找到与您问题相关的内容。请上传相关文档后再提问。";
        }

        // 2. 构建 Prompt
        String prompt = buildPrompt(relevantDocs, question);

        // 3. 调用大模型生成回答
        String answer = chatClientBuilder.build()
                .prompt()
                .user(prompt)
                .call()
                .content();

        long endTime = System.currentTimeMillis();
        log.info("✅ RAG问答完成，耗时: {}ms", endTime - startTime);

        return answer;
    }

    /**
     * 流式 RAG 问答
     */
    @Override
    public Flux<String> chatWithDocumentStream(String question) {
        log.info("🔍 收到流式RAG问答请求: {}", question);
        long startTime = System.currentTimeMillis();

        // 优先匹配本地知识库
        String localAnswer = localKnowledgeService.match(question);
        if (localAnswer != null) {
            log.info("✅ 本地知识库命中，返回流式");
            return Flux.just(localAnswer);
        }

        // 1. 检索相关文档片段（阻塞操作，但很快）
        Long userId = getCurrentUserId();
        List<Document> relevantDocs = retrieveDocuments(question, userId);

        if (relevantDocs.isEmpty()) {
            return Flux.just("抱歉，在知识库中未找到与您问题相关的内容。请上传相关文档后再提问。");
        }

        // 2. 构建 Prompt
        String prompt = buildPrompt(relevantDocs, question);

        // 3. 流式调用大模型
        return chatClientBuilder.build()
                .prompt()
                .user(prompt)
                .stream()
                .content()
                .doOnComplete(() -> {
                    long endTime = System.currentTimeMillis();
                    log.info("✅ 流式RAG问答完成，耗时: {}ms", endTime - startTime);
                });
    }

    /**
     * 支持工具调用的问答（手动解析 JSON）
     *
     * @param userMessage 用户问题
     * @return 最终回答
     */
    public String chatWithTool(String userMessage) {
        log.info("🔧 进入工具调用模式，问题: {}", userMessage);

        // 优先匹配本地知识库
        String localAnswer = localKnowledgeService.match(userMessage);
        if (localAnswer != null) {
            log.info("✅ 本地知识库命中，直接返回");
            return localAnswer;
        }

        // 1. 构造 Prompt，要求模型如果认为需要工具，则以 JSON 格式返回
        String toolPrompt = String.format("""
                你是一个智能助手，可以调用工具获取信息。
                如果用户的问题需要查询实时天气或新闻，请返回一个 JSON 对象，格式为：
                {"name": "工具名称", "arguments": {"参数名": "参数值"}}
                可用的工具：
                - getWeather: 查询天气，参数 city（城市名）
                - getAINews: 获取AI新闻，参数 limit（数量）和 window（时间窗口，如24h、7d）
                如果不需要工具，请直接回答用户的问题。
                
                用户问题：%s
                """, userMessage);

        ChatClient chatClient = chatClientBuilder.build();

        // 2. 第一次调用，获取模型响应
        String firstResponse = chatClient.prompt()
                .user(toolPrompt)
                .call()
                .content();

        log.debug("第一次响应: {}", firstResponse);

        // 3. 检查是否为工具调用 JSON
        if (toolExecutor.isToolCall(firstResponse)) {
            log.info("🔧 检测到工具调用: {}", firstResponse);
            // 执行工具
            String toolResult = toolExecutor.execute(firstResponse);
            log.info("🔧 工具执行结果: {}", toolResult);

            // 4. 第二次调用，将工具结果融入回答
            String finalPrompt = String.format("""
                    用户问题：%s
                    
                    工具返回的结果：%s
                    
                    请根据工具返回的结果，用自然流畅的中文回答用户的问题。
                    如果工具结果无法回答，请友好地说明。
                    """, userMessage, toolResult);

            return chatClient.prompt()
                    .user(finalPrompt)
                    .call()
                    .content();
        }

        // 如果不是工具调用，直接返回
        return firstResponse;
    }

    private List<Document> retrieveDocuments(String question, Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return Collections.emptyList();
        }

        // 1. 构建 Qdrant Filter
        Common.Filter filter = buildQdrantFilter(user);

        // 2. 获取查询向量（float[]）
        float[] queryVector = embeddingModel.embed(question);

        // 3. 转换为 List<Float>
        List<Float> vectorList = new ArrayList<>(queryVector.length);
        for (float v : queryVector) {
            vectorList.add(v);
        }

        // 4. 构建 SearchPoints 请求
        Points.SearchPoints.Builder searchBuilder = Points.SearchPoints.newBuilder()
                .setCollectionName(collectionName)
                .setLimit(3)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                .addAllVector(vectorList);  // 使用 addAllVector 添加整个向量

        if (filter != null) {
            searchBuilder.setFilter(filter);
        }

        Points.SearchPoints searchRequest = searchBuilder.build();

        try {
            List<Points.ScoredPoint> scoredPoints = qdrantClient.searchAsync(searchRequest).get();

            List<Document> documents = new ArrayList<>();

            for (Points.ScoredPoint scoredPoint : scoredPoints) {
                Map<String, Object> metadata = new HashMap<>();
                String content = null;

                // payload 使用 JsonWithInt.Value
                Set<Map.Entry<String, JsonWithInt.Value>> entries = scoredPoint.getPayloadMap().entrySet();

                for (Map.Entry<String, JsonWithInt.Value> entry : entries) {
                    String key = entry.getKey();
                    JsonWithInt.Value value = entry.getValue();

                    if ("doc_content".equals(key)) {
                        content = value.getStringValue();
                    } else {
                        if (value.hasStringValue()) {
                            metadata.put(key, value.getStringValue());
                        } else if (value.hasIntegerValue()) {
                            metadata.put(key, value.getIntegerValue());
                        } else if (value.hasDoubleValue()) {
                            metadata.put(key, value.getDoubleValue());
                        } else if (value.hasBoolValue()) {
                            metadata.put(key, value.getBoolValue());
                        } else if (value.hasNullValue()) {
                            metadata.put(key, null);
                        }
                    }
                }

                if (content != null && !content.trim().isEmpty()) {
                    Document doc = new Document(content, metadata);
                    documents.add(doc);
                }
            }

            log.info("📚 检索到 {} 个相关文档片段（已按权限过滤）", documents.size());
            return documents;
        } catch (Exception e) {
            log.error("检索失败", e);
            return Collections.emptyList();
        }
    }


    /**
     * 构建 Prompt（抽取为公共方法）
     */
    private String buildPrompt(List<Document> relevantDocs, String question) {
        String context = relevantDocs.stream()
                .map(Document::getFormattedContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        log.info("📝 构建Prompt，上下文长度: {} 字符", context.length());

        return """
                请根据以下文档内容回答用户的问题。
                
                文档内容：
                %s
                
                用户问题：%s
                
                回答要求：
                1. 只能基于上述文档内容回答
                2. 如果文档中没有相关信息，请明确说明"文档中未找到相关信息"
                3. 回答要简洁、准确，并用中文
                4. 引用文档中的原文时，请用引号标注
                """.formatted(context, question);
    }



    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("用户未登录");
        }
        String username = authentication.getName();
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", username).or().eq("email", username)
        );
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user.getId();
    }



    private Common.Filter buildQdrantFilter(SysUser user) {
        // 管理员：无过滤
        if (user.getIsAdmin() == 1) {
            return null;
        }

        Common.Filter.Builder filterBuilder = Common.Filter.newBuilder();

        // 外部用户：仅公开文档
        if (user.getUserType() != null && user.getUserType() == 2) {
            Common.FieldCondition fieldCondition = Common.FieldCondition.newBuilder()
                    .setKey("is_public")
                    .setMatch(Common.Match.newBuilder()
                            .setKeyword("1")
                            .build())
                    .build();
            Common.Condition condition = Common.Condition.newBuilder()
                    .setField(fieldCondition)
                    .build();
            return filterBuilder.addMust(condition).build();
        }

        // 内部用户：本部门文档 + 公开文档
        Long departmentId = user.getDepartmentId();
        if (departmentId != null && departmentId > 0) {
            // OR 条件
            // 条件1: department_id == 用户部门
            Common.FieldCondition deptField = Common.FieldCondition.newBuilder()
                    .setKey("department_id")
                    .setMatch(Common.Match.newBuilder()
                            .setKeyword(departmentId.toString())
                            .build())
                    .build();
            Common.Condition deptCondition = Common.Condition.newBuilder()
                    .setField(deptField)
                    .build();

            // 条件2: is_public == 1
            Common.FieldCondition pubField = Common.FieldCondition.newBuilder()
                    .setKey("is_public")
                    .setMatch(Common.Match.newBuilder()
                            .setKeyword("1")
                            .build())
                    .build();
            Common.Condition pubCondition = Common.Condition.newBuilder()
                    .setField(pubField)
                    .build();

            // 构建 OR: should 至少一个匹配
            return Common.Filter.newBuilder()
                    .addShould(deptCondition)
                    .addShould(pubCondition)
                    .build();
        } else {
            // 用户无部门，仅公开文档
            Common.FieldCondition pubField = Common.FieldCondition.newBuilder()
                    .setKey("is_public")
                    .setMatch(Common.Match.newBuilder()
                            .setKeyword("1")
                            .build())
                    .build();
            Common.Condition pubCondition = Common.Condition.newBuilder()
                    .setField(pubField)
                    .build();
            return Common.Filter.newBuilder().addMust(pubCondition).build();
        }
    }
}