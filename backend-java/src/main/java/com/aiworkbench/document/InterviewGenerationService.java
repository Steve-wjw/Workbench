package com.aiworkbench.document;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.aiworkbench.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 调用 OpenAI 兼容的聊天接口，将资料转换为待确认的面经草稿。
 *
 * <p>生成结果只返回给当前请求，不在服务端持久化；用户确认后仍通过原有面经接口保存。
 */
@Service
public class InterviewGenerationService {
    private static final int MAX_QUESTIONS = 3;
    private static final int MAX_CONTENT_LENGTH = 14000;

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public InterviewGenerationService(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${workbench.llm.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${workbench.llm.api-key:}") String apiKey,
            @Value("${workbench.llm.model:deepseek-chat}") String model) {
        this.client = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory())
                .build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 根据资料生成最多三条面经草稿。
     *
     * @param title 资料标题
     * @param content 资料正文
     * @return 待确认的面经草稿
     */
    public InterviewGenerationModels.GeneratedQuestionsResponse generate(String title, String content) {
        if (apiKey == null || apiKey.isBlank()) {
            throw unavailable("尚未配置模型 API Key，无法生成面经。");
        }
        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_DOCUMENT", "资料内容为空，无法生成面经。");
        }

        String prompt = """
                你是一个严谨的 AI 面试题整理助手。请只根据给定资料生成最多 3 条适合面试复习的题目。
                不要引入资料中没有的事实，不要生成与资料无关的问题。
                每条题目必须包含：
                - question：面试官可能提出的问题
                - answer：简洁、可直接复述的答案
                - detailedAnswer：基于资料的详细解释
                - difficulty：只能是 EASY、MEDIUM、HARD
                只返回 JSON，不要 Markdown 代码块，不要额外说明，格式必须是：
                {"questions":[{"question":"...","answer":"...","detailedAnswer":"...","difficulty":"MEDIUM"}]}

                资料标题：
                %s

                资料内容：
                %s
                """.formatted(title == null ? "" : title, content.substring(0, Math.min(content.length(), MAX_CONTENT_LENGTH)));

        try {
            JsonNode response = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(Map.of(
                            "model", model,
                            "temperature", 0.2,
                            "max_tokens", 3000,
                            "response_format", Map.of("type", "json_object"),
                            "messages", List.of(
                                    Map.of("role", "system", "content", "你只输出合法 JSON。"),
                                    Map.of("role", "user", "content", prompt))))
                    .retrieve()
                    .body(JsonNode.class);
            return parseResponse(response);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable("模型服务暂时不可用，请检查 API 配置或稍后重试。");
        }
    }

    private InterviewGenerationModels.GeneratedQuestionsResponse parseResponse(JsonNode response) {
        String content = response == null
                ? ""
                : response.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw unavailable("模型没有返回有效的面经内容。");
        }
        try {
            JsonNode questions = objectMapper.readTree(stripCodeFence(content)).path("questions");
            List<InterviewGenerationModels.GeneratedQuestion> result = new ArrayList<>();
            if (!questions.isArray()) {
                throw unavailable("模型返回格式不正确。");
            }
            for (JsonNode item : questions) {
                String question = item.path("question").asText("").strip();
                String answer = item.path("answer").asText("").strip();
                String detailedAnswer = item.path("detailedAnswer").asText("").strip();
                String difficulty = item.path("difficulty").asText("MEDIUM").strip().toUpperCase();
                if (!question.isBlank() && !answer.isBlank() && List.of("EASY", "MEDIUM", "HARD").contains(difficulty)) {
                    result.add(new InterviewGenerationModels.GeneratedQuestion(question, answer, detailedAnswer, difficulty));
                }
                if (result.size() == MAX_QUESTIONS) {
                    break;
                }
            }
            if (result.isEmpty()) {
                throw unavailable("模型没有生成可用的面经内容。");
            }
            return new InterviewGenerationModels.GeneratedQuestionsResponse(List.copyOf(result));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("模型返回内容无法解析，请稍后重试。");
        }
    }

    private String stripCodeFence(String value) {
        String trimmed = value.strip();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            return firstLineEnd >= 0
                    ? trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).strip()
                    : trimmed.substring(3, trimmed.length() - 3).strip();
        }
        return trimmed;
    }

    private ApiException unavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "INTERVIEW_GENERATION_UNAVAILABLE", message);
    }

    private static org.springframework.http.client.JdkClientHttpRequestFactory requestFactory() {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(45));
        return factory;
    }
}
