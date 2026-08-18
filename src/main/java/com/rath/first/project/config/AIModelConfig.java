package com.rath.first.project.config;

import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AIModelConfig {

    private final String flashModel;
    private final String proModel;
    private final String chatModel;

    public AIModelConfig(
            @Value("${spring.ai.google.genai.models.flash:gemini-2.5-flash}") String flashModel,
            @Value("${spring.ai.google.genai.models.pro:gemini-2.5-pro}") String proModel,
            @Value("${spring.ai.google.genai.chat.model}") String chatModel) {
        this.flashModel = flashModel;
        this.proModel = proModel;
        this.chatModel = chatModel;
    }

    /**
     * ROUTE: Cheap / Fast Model (Flash)
     * USE CASE: Ticket triage, structured JSON extraction, classification, sentiment analysis.
     * STRATEGY: Zero randomness (temp 0.0), topK 1 for strict precision, Flash model for minimum latency & cost.
     */
    public GoogleGenAiChatOptions.Builder forClassification() {
        return GoogleGenAiChatOptions.builder()
                .model(flashModel)
                .temperature(0.0)
                .topK(1)
                .topP(0.1);
    }

    /**
     * ROUTE: High-Reasoning Model (Pro)
     * USE CASE: Complex Java architecture, JVM mechanics, interview evaluation, deep debugging.
     * STRATEGY: Zero randomness for factual accuracy, max context allocation, Pro model for deep logic.
     */
    public GoogleGenAiChatOptions.Builder forTechnicalAnalysis() {
        return GoogleGenAiChatOptions.builder()
                .model(proModel)
                .temperature(0.0)
                .topK(40)
                .topP(0.95)
                .maxOutputTokens(1000)
                .stopSequences(List.of("---", "END_OF_ANSWER"));
    }

    /**
     * ROUTE: High-Reasoning Model (Pro) in Creative Mode
     * USE CASE: Ideation, generating system design interview scenarios, brainstorming edge cases.
     * STRATEGY: Higher temperature (0.8) for varied output, Pro model for reasoning depth.
     */
    public GoogleGenAiChatOptions.Builder forCreativeBrainstorming() {
        return GoogleGenAiChatOptions.builder()
                .model(proModel)
                .temperature(0.8)
                .topK(40)
                .topP(0.95)
                .maxOutputTokens(1000);
    }

    /**
     * ROUTE: General Conversation (the main "/" chat endpoint, with memory)
     * USE CASE: Interactive Q&A assistant. Pass creative=true to loosen it up for brainstorming.
     * STRATEGY: Uses the default chat model; toggles temperature for factual vs. varied output.
     * (Moved here from ChatController.)
     * NOTE: returns a Builder (not built options) because the per-request ChatClient
     * `.options(...)` in Spring AI 2.0 expects a ChatOptions.Builder.
     */
    public GoogleGenAiChatOptions.Builder forConversation(boolean creative) {
        // --- SAMPLING PARAMETER CONFIGURATION ---
        // Low Temperature (0.0): Strict, reproducible, interview-grade facts.
        // High Temperature (0.8): Brainstorming, varied interview question scenarios.
        double targetTemperature = creative ? 0.8 : 0.0;

        // Top-K (10): Only consider top 40 candidate tokens at each step.
        // Top-P (0.95): cumulative probability reaches at least 0.95. The remaining probability mass, roughly 0.05, is excluded
        int topK = 10;
        double topP = 0.95;

        return GoogleGenAiChatOptions.builder()
                .model(chatModel)
                .temperature(targetTemperature)
                .topK(topK)
                .topP(topP)
                // Stop Sequences: Halt generation immediately if the model generates these delimiters.
                .stopSequences(List.of("END_OF_ANSWER", "### SUMMARY", "---"))
                .maxOutputTokens(500);
    }
}
