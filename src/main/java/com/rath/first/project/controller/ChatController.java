package com.rath.first.project.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The main chat endpoint: a back-and-forth assistant that remembers the conversation.
 *
 * Three important ideas live in this class:
 *   1. A "system prompt" — permanent instructions that give the AI its role and rules.
 *   2. "Memory" — the AI itself is forgetful (every request starts blank), so we
 *      re-send recent messages each time to fake a continuous conversation.
 *   3. "Token logging" — every call costs money measured in tokens, so we record them.
 */
@RestController
public class ChatController {

    // Standard logger — we'll use it to record how many tokens each call used.
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;
    private final String modelName;

    public ChatController(
            ChatClient.Builder chatClientBuilder,
            // Reads the model name from application.properties so it's not hard-coded here.
            @Value("${spring.ai.google.genai.chat.model}") String modelName) {

        this.modelName = modelName;

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
                .defaultSystem("""
                    You are a world-class Lead Java Engineer and Technical Interviewer.
                    
                    ### OBJECTIVE
                    Prepare candidates for top-tier Java backend and JVM interviews using accurate, real-time industry context and modern Java specs (JDK 17 through JDK 25+).
                    
                    ### CORE GUIDELINES
                    1. ACCURACY OVER HYPE: Always evaluate features based on low-level mechanics (JVM implementation, GC impact, memory overhead, thread safety) rather than high-level syntax shortcuts.
                    2. PRODUCTION REALITY: When explaining interview concepts (e.g., Virtual Threads, Structured Concurrency, Scoped Values, Garbage Collectors), highlight practical trade-offs, failure modes, and when NOT to use them.
                    3. STRUCTURED OUTPUT:
                       - Start directly with the core concept and execution mechanics.
                       - Provide concise, production-grade code snippets where applicable.
                       - Limit responses to at most 4 sharp, highly informative bullet points or sentences.
                    4. NO FLUFF: Skip introductory setups, polite conversational filler, or generic closing remarks. Jump immediately into the technical answer.
                    """)
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
            @RequestParam(value = "conversationId", defaultValue = "default-session") String conversationId) {

        ChatResponse chatResponse = chatClient.prompt()
                .user(userQuery)
                // Tell the memory advisor WHICH conversation's history to load and update.
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                // Per-request settings:
                //   model         -> which AI model to use
                //   temperature 0 -> consistent, focused answers (higher = more creative)
                //   maxOutputTokens 500 -> a hard cap on answer length = a cap on cost & time
                .options(GoogleGenAiChatOptions.builder()
                        .model(modelName)
                        .temperature(0.0)
                        .maxOutputTokens(500))
                .call()
                // .chatResponse() gives us the FULL response (text + usage stats), not just
                // the text — we need the extra info to log token usage below.
                .chatResponse();

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

        // Pull the plain text answer out of the full response to return to the caller.
        // If something went wrong and there's no result, return an empty string instead.
        return chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput().getText()
                : "";
    }
}