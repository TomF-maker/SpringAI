package com.example.springai.service;

import com.example.springai.entity.SourceRef;
import reactor.core.publisher.Flux;

import java.util.List;

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
     *
     * <p>sources 是检索命中的文档片段来源标注，可为 null/空 —— 走兜底、本地知识库、
     * 工具调用等无检索路径时不带来源，前端据此不渲染"来源"区。
     */
    class Answer {
        private final Long questionLogId;
        private final String answer;
        private final List<SourceRef> sources;

        /** 兼容旧调用：无来源标注的问答（工具调用、本地知识库等路径）。 */
        public Answer(Long questionLogId, String answer) {
            this(questionLogId, answer, null);
        }

        public Answer(Long questionLogId, String answer, List<SourceRef> sources) {
            this.questionLogId = questionLogId;
            this.answer = answer;
            this.sources = sources;
        }

        public Long getQuestionLogId() {
            return questionLogId;
        }

        public String getAnswer() {
            return answer;
        }

        public List<SourceRef> getSources() {
            return sources;
        }
    }

    /** 流式问答的结果：日志 id + 内容流 + 来源标注。 */
    class AnswerStream {
        private final Long questionLogId;
        private final Flux<String> content;
        private final List<SourceRef> sources;

        /** 兼容旧调用：无来源标注的流式问答（兜底、本地知识库等路径）。 */
        public AnswerStream(Long questionLogId, Flux<String> content) {
            this(questionLogId, content, null);
        }

        public AnswerStream(Long questionLogId, Flux<String> content, List<SourceRef> sources) {
            this.questionLogId = questionLogId;
            this.content = content;
            this.sources = sources;
        }

        public Long getQuestionLogId() {
            return questionLogId;
        }

        public Flux<String> getContent() {
            return content;
        }

        public List<SourceRef> getSources() {
            return sources;
        }
    }

    /**
     * 基于知识库的智能问答
     *
     * @param question       用户问题
     * @param conversationId 所属会话，仅用于提问埋点；匿名或不带会话时传 null
     * @param clientIp       完整客户端 IP，仅用于提问埋点的归属地；取不到传 null
     * @return 基于文档内容的回答，附带这次提问的埋点 id
     */
    Answer chatWithDocument(String question, String conversationId, String clientIp);

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
     * @param userMessage    用户问题
     * @param conversationId 所属会话，仅用于提问埋点；匿名或不带会话时传 null
     * @param clientIp       完整客户端 IP，仅用于提问埋点的归属地；取不到传 null
     * @return 最终回答，附带这次提问的埋点 id
     */
    Answer chatWithTool(String userMessage, String conversationId, String clientIp);
}
