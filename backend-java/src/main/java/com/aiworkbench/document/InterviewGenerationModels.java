package com.aiworkbench.document;

import java.util.List;

/** 资料生成面经的临时响应模型，不直接写入数据库。 */
public final class InterviewGenerationModels {

    private InterviewGenerationModels() {
    }

    /** 一条待确认的面经草稿。 */
    public record GeneratedQuestion(
            String question,
            String answer,
            String detailedAnswer,
            String difficulty) {
    }

    /** 生成结果。 */
    public record GeneratedQuestionsResponse(
            List<GeneratedQuestion> questions) {
    }
}
