package com.rath.first.project.controller;

import com.rath.first.project.dto.TicketTriage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Turns a free-text support ticket into structured data using the AI.
 *
 * This is the "structured output" idea: we don't want a chatty paragraph back,
 * we want clean fields (category, priority, summary) we can store or act on.
 *
 * Try it:
 *   POST /api/v1/tickets/triage
 *   body: "My invoice #4411 was charged twice and I need a refund today"
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TriageController {

    private final ChatClient chatClient;

    // Spring hands us a pre-configured builder; we build a simple client from it.
    public TriageController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @PostMapping("/triage")
    public TicketTriage triageTicket(@RequestBody String ticketDescription) {
        return chatClient.prompt()
                // The user's ticket text becomes part of the question we send the model.
                .user("Triage this support ticket: " + ticketDescription)
                // temperature 0.0 = be consistent and factual, not creative.
                // For classification tasks we want the same input to give the same answer.
                .options(GoogleGenAiChatOptions.builder().temperature(0.0))
                .call()
                // .entity(...) is the magic step: Spring AI asks the model to reply in the
                // shape of TicketTriage, then parses that reply into a real Java object.
                .entity(TicketTriage.class);
    }
}