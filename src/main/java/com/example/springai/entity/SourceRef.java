package com.example.springai.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次问答里命中的文档片段来源标注。
 *
 * <p>咨询客户对"答得有理有据"极敏感 —— 没有来源标注，模型哪怕答对了，
 * 客户也会怀疑是 AI 自己编的。把检索命中的片段原样带出来，前端在回答末尾
 * 展示「来源：《XX 报告》」可展开看片段，客户才能验证答案的出处。
 *
 * <p>这个类既作为 RAG 回答的返回字段（序列化给前端），也作为会话消息
 * {@link Message#getSources()} 的一部分持久化到 MongoDB。所以它必须是可反序列化的
 * 普通 POJO（带无参构造 + setter），而不是 RagServiceI 里的不可变内部类 ——
 * 否则历史消息读回来时 Jackson 反序列化会直接失败。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceRef {
    /** 命中的文档在 kb_document 表里的 id；payload 没带时为 null */
    private Long documentId;
    /** 文档标题（当前取 Qdrant payload 里的 source，即上传文件名） */
    private String documentTitle;
    /** 命中的文档片段原文，截断后展示，方便客户核对 */
    private String chunkText;
    /** Qdrant 余弦相似度，越大越相关；阈值 0.5 是实测分界线 */
    private Double score;
}
