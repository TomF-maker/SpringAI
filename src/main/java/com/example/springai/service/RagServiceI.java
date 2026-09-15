package com.example.springai.service;

import reactor.core.publisher.Flux;

/**
 * RAG（检索增强生成）服务接口
 * <p>
 * 职责：
 * 1. 接收用户问题，从向量库中检索相关文档片段
 * 2. 将检索结果与问题组合成提示词
 * 3. 调用大模型生成基于文档的回答
 */
public interface RagServiceI {

    /**
     * 一次问答的结果。
     *
     * <p>除了答案本身，还把这次提问在 {@code kb_question_log} 里的 id 带出来 ——
     * 前端提交答案评价时要靠它关联到具体是哪一次提问。
     * 有了这个关联才能分析"哪类问题容易被打低分"，否则只知道"有人不满意"。
     * 埋点表不可用/写入失败时为 null。
     */
    class Answer {
        private final Long questionLogId;
        private final String answer;

        public Answer(Long questionLogId, String answer) {
            this.questionLogId = questionLogId;
            this.answer = answer;
        }

        public Long getQuestionLogId() {
            return questionLogId;
        }

        public String getAnswer() {
            return answer;
        }
    }

    /** 流式问答的结果：日志 id + 内容流。 */
    class AnswerStream {
        private final Long questionLogId;
        private final Flux<String> content;

        public AnswerStream(Long questionLogId, Flux<String> content) {
            this.questionLogId = questionLogId;
            this.content = content;
        }

        public Long getQuestionLogId() {
            return questionLogId;
        }

        public Flux<String> getContent() {
            return content;
        }
    }

    /**
     * 基于知识库的智能问答
     *
     * @param question 用户问题
     * @param clientIp 完整客户端 IP，仅用于提问埋点的归属地；取不到传 null
     * @return 基于文档内容的回答，附带这次提问的埋点 id
     */
    Answer chatWithDocument(String question, String clientIp);

    /**
     * 基于知识库的智能问答（流式输出）
     *
     * @param question       用户问题
     * @param conversationId 所属会话 id，用于提问埋点；匿名或非流式路径没有会话，传 null
     * @param clientIp       完整客户端 IP，仅用于提问埋点的归属地；取不到传 null
     * @return 日志 id + 流式返回的回答片段
     */
    AnswerStream chatWithDocumentStream(String question, String conversationId, String clientIp);

    /**
     * 支持工具调用的问答（手动解析 JSON）
     *
     * @param userMessage 用户问题
     * @param clientIp    完整客户端 IP，仅用于提问埋点的归属地；取不到传 null
     * @return 最终回答，附带这次提问的埋点 id
     */
    Answer chatWithTool(String userMessage, String clientIp);
}
