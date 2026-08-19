package com.rath.first.project.controller;

import com.rath.first.project.config.AIModelConfig;
import com.rath.first.project.service.ResilientChatService;
import com.rath.first.project.service.TokenGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Three important ideas live in this class:
 *   1. A "system prompt" — permanent instructions that give the AI its role and rules.
 *   2. "Memory" — the AI itself is forgetful (every request starts blank), so we
 *      re-send recent messages each time to fake a continuous conversation.
 *   3. Now not caching system prompt (far too small to cache)
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;
    private final AIModelConfig aiModelConfig;
    private final TokenGuardService tokenGuard;
    private final ResilientChatService resilientChatService;

    public ChatController(
            ChatClient.Builder chatClientBuilder,
            AIModelConfig aiModelConfig,
            TokenGuardService tokenGuard,
            ResilientChatService resilientChatService,
            // The system prompt now lives in its own file (prompts are code — easy to review & version).
            @Value("classpath:/prompts/system-persona.st") Resource systemPrompt) {

        this.aiModelConfig = aiModelConfig;
        this.tokenGuard = tokenGuard;
        this.resilientChatService = resilientChatService;

        // --- MEMORY SETUP ---
        // The AI has no built-in memory: each API call is independent and knows nothing
        // about earlier messages. To make it feel like a real conversation, we keep a
        // short history and replay it on every call.
        // "Window" = only keep the most recent messages (here, the last 10) so the
        // history can't grow forever and blow past the model's size/cost limits.
        // InMemoryChatMemoryRepository = history is stored in RAM (lost on restart);
        // fine for learning, but you'd swap in a database for a real app.
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();

        // An "advisor" is a plug-in that sits around every chat call. This one
        // automatically injects the remembered history into each request for us.
        MessageChatMemoryAdvisor chatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        // --- BUILD THE REUSABLE CHAT CLIENT ---
        this.chatClient = chatClientBuilder
                // The SYSTEM PROMPT: standing instructions the AI always follows. It sets
                // the assistant's persona, rules, and answer style. Users never see or
                // control this — it's the highest-authority voice in the conversation.
                // Loaded from classpath:/prompts/system-persona.st (see the constructor param).
                .defaultSystem(systemPrompt)
                // Attach our plug-ins to every call made with this client:
                //   SimpleLoggerAdvisor  -> logs the full prompt/response (handy while learning)
                //   chatMemoryAdvisor    -> replays recent history so the chat feels continuous
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        chatMemoryAdvisor
                )
                .build();
    }

    @GetMapping("/")
    public String getResponse(
            // The user's question. Has a default so you can test by just opening "/".
            @RequestParam(value = "userQuery", defaultValue = "What is Virtual Thread pinning?") String userQuery,
            // Which conversation this message belongs to. Two people (or two browser tabs)
            // using different IDs get separate, independent memories. Same ID = same thread.
            @RequestParam(value = "conversationId", defaultValue = "default-session") String conversationId,
            @RequestParam(value = "creative", defaultValue = "false") boolean creative) {

        // --- PRE-FLIGHT TOKEN CAP ---
        // Ask Gemini how big the input is and reject it (HTTP 413) if it's over our temporary
        // cap, BEFORE spending a full billable generation call on it. (Counts the user's query;
        // the real request also includes the system prompt + replayed history.)
        tokenGuard.ensureWithinCap(userQuery);

        // --- RESILIENT CALL: timeout + circuit breaker wrap the slow, remote LLM call ---
        ChatResponse chatResponse = resilientChatService.execute(() -> chatClient.prompt()
                .user(userQuery)
                // Tell the memory advisor WHICH conversation's history to load and update.
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                // Route the request based on user input (the "creative" flag):
                //   creative=true  -> forCreativeBrainstorming() (higher temperature, varied output)
                //   creative=false -> forTechnicalAnalysis()     (temperature 0, factual & precise)
                // The model + sampling settings for each route live in AIModelConfig.
                .options(creative
                        ? aiModelConfig.forCreativeBrainstorming()
                        : aiModelConfig.forTechnicalAnalysis())
                .call()
                // .chatResponse() gives us the FULL response (text + usage stats), not just
                // the text — we need the extra info to log token usage below.
                .chatResponse());

        // --- COST TRACKING ---
        // "Tokens" are the chunks of text the model reads and writes, and they're what you
        // pay for. Logging them per call is your early-warning system for surprise bills.
        // The null-checks guard against a missing/failed response.
        if (chatResponse != null && chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            log.info("Conversation [{}] -> tokens in={} out={} total={}",
                    conversationId,
                    usage.getPromptTokens(),      // tokens we sent (question + system prompt + history)
                    usage.getCompletionTokens(),  // tokens the AI generated in its reply
                    usage.getTotalTokens());      // the sum — what actually gets billed
        }

        return chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput().getText()
                : "";
    }
}