package com.rath.first.project.controller;

import com.rath.first.project.config.AIModelConfig;
import com.rath.first.project.dto.TicketTriage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * structured data using the AI.
 * This is the "structured output" idea: we don't want a chatty paragraph back,
 * we want clean fields (category, priority, summary) we can store or act on.
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TriageController {

    private final ChatClient chatClient;
    private final AIModelConfig aiModelConfig;

    public TriageController(ChatClient.Builder chatClientBuilder,
                            AIModelConfig aiModelConfig) {
        this.chatClient = chatClientBuilder.build();
        this.aiModelConfig = aiModelConfig;
    }

    // CACHE: triage is a pure function of the ticket text — temperature 0 (deterministic)
    // and no conversation memory — so the same ticket always yields the same result.
    // That makes it safe to cache: a repeat of an identical ticket returns instantly and
    // skips the paid, slow API call. The cache key defaults to the only argument (ticketDescription).
    // (We deliberately do NOT cache the "/" chat endpoint: it has memory and a creative
    //  mode, so identical questions can legitimately produce different answers.)
    @PostMapping("/triage")
    @Cacheable("ticketTriage")
    public TicketTriage triageTicket(@RequestBody String ticketDescription) {
        return chatClient.prompt()
                // The user's ticket text becomes part of the question we send the model.
                .user("Triage this support ticket: " + ticketDescription)
                // temperature 0.0 = be consistent and factual, not creative.
                // For classification tasks we want the same input to give the same answer.
                .options(aiModelConfig.forClassification())
                .call()
                // .entity(...) is the magic step: Spring AI asks the model to reply in the
                // shape of TicketTriage, then parses that reply into a real Java object.
                .entity(TicketTriage.class);
    }
}