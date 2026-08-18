package com.rath.first.project.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Streams the AI's answer back piece by piece, instead of waiting for the whole thing.
 *
 * A full answer can take many seconds to finish. But the model starts producing
 * words almost immediately, so we send each chunk to the browser as it arrives.
 * The user sees text appear live (like ChatGPT typing) — the total time is the
 * same, but it *feels* much faster.
 */
@RestController
public class ChatStreamController {

    private final ChatClient chatClient;

    public ChatStreamController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    // produces = TEXT_EVENT_STREAM_VALUE tells the browser "this response arrives in
    // pieces over time" (a format called Server-Sent Events), not all at once.
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam("q") String userQuery) {
        // A Flux is a stream of values that show up over time. Here it's a stream
        // of text chunks. .stream() (instead of .call()) asks for that live feed.
        return chatClient.prompt()
                .user(userQuery)
                .stream()
                .content();
    }
}
