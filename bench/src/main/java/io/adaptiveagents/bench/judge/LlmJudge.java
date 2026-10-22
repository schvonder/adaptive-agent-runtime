package io.adaptiveagents.bench.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.bench.config.BenchProfileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Evaluates research output quality using an LLM judge on a 0-5 integer rubric.
 * When {@code chatClient} is null (stub/test mode), evaluation executes a deterministic heuristic.
 */
public class LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final BenchProfileConfig.JudgeConfig judgeConfig;

    public LlmJudge() {
        this(null, new BenchProfileConfig.JudgeConfig(
                "", "", "", 0.0, 42, "0-5", "Rubric 0-5"));
    }

    public LlmJudge(ChatClient chatClient, BenchProfileConfig.JudgeConfig judgeConfig) {
        this.chatClient = chatClient;
        this.judgeConfig = judgeConfig;
    }

    public int evaluate(String prompt, String reference, String output) {
        if (output == null || output.isBlank()) {
            return 0;
        }

        if (chatClient == null) {
            return evaluateDeterministicStub(output);
        }

        try {
            String systemPrompt = """
                    You are an expert impartial judge evaluating the quality of an autonomous agent research response.
                    Rubric scale: 0 to 5 integer.
                    0: Empty, catastrophic failure, or completely irrelevant.
                    1: Minimal response with major hallucinations or factual errors.
                    2: Partially answers prompt with substantial omissions.
                    3: Adequate response covering core requirements with minor gaps.
                    4: Strong, comprehensive, well-structured answer with verifiable citations.
                    5: Outstanding synthesis, fully grounded in reference material with rigorous reasoning.
                    Respond ONLY with JSON: {"score": <integer 0-5>, "reasoning": "<concise justification>"}
                    """;

            String userContent = """
                    Task Prompt: %s
                    Reference Material: %s
                    Agent Output: %s
                    """.formatted(prompt, reference == null ? "None" : reference, output);

            String responseText = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userContent)
                    .options(OpenAiChatOptions.builder()
                            .model(judgeConfig.model())
                            .temperature(judgeConfig.temperature()))
                    .call()
                    .content();

            if (responseText != null) {
                String clean = responseText.trim();
                if (clean.startsWith("```json")) {
                    clean = clean.substring(7);
                } else if (clean.startsWith("```")) {
                    clean = clean.substring(3);
                }
                if (clean.endsWith("```")) {
                    clean = clean.substring(0, clean.length() - 3);
                }
                JsonNode json = MAPPER.readTree(clean.trim());
                if (json.has("score")) {
                    int score = json.get("score").asInt();
                    return Math.max(0, Math.min(5, score));
                }
            }
            return 3;
        } catch (Exception e) {
            log.warn("Judge evaluation failed, falling back to heuristic: {}", e.getMessage());
            return evaluateDeterministicStub(output);
        }
    }

    private int evaluateDeterministicStub(String output) {
        if (output == null || output.isBlank()) {
            return 0;
        }
        if (output.toLowerCase().contains("failed") || output.toLowerCase().contains("error")) {
            return 1;
        }
        if (output.contains("plan") && output.contains("summary") && output.contains("citations")) {
            return 5;
        }
        return 4;
    }
}
