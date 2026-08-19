package com.rath.first.project.service;

import com.google.genai.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pre-flight token guard.
 *
 * Before sending a prompt to Gemini, we ask Gemini's countTokens API how many tokens the
 * input is, and refuse the request if it's over a cap. This does two useful things:
 *   - avoids the 400 error you'd get for blowing past the model's context window, and
 *   - protects you from surprise cost/latency on an accidentally huge input.
 *
 * NOTE: countTokens is a separate (cheap) network call. In production you'd often use a
 * local tokenizer or a cached estimate instead of a round-trip on every request. The cap
 * here is deliberately low and TEMPORARY — it's here to demonstrate the mechanism.
 */
@Service
public class TokenGuardService {

    private static final Logger log = LoggerFactory.getLogger(TokenGuardService.class);

    private final Client client;        // the SAME Google GenAI client Spring AI auto-configures
    private final String model;         // model to count against (counts are ~similar across Gemini models)
    private final int maxInputTokens;   // temporary hard cap

    public TokenGuardService(
            Client client,
            @Value("${spring.ai.google.genai.chat.model}") String model,
            @Value("${app.tokens.max-input:1000}") int maxInputTokens) {
        this.client = client;
        this.model = model;
        this.maxInputTokens = maxInputTokens;
    }

    /** Ask Gemini how many tokens a piece of text is. */
    public int count(String text) {
        return client.models.countTokens(model, text, null)
                .totalTokens()
                .orElse(0);
    }

    /**
     * Reject with HTTP 413 (Payload Too Large) if the text exceeds our temporary input cap.
     * Returns the token count so the caller can log it if it wants.
     */
    public int ensureWithinCap(String text) {
        int tokens = count(text);
        if (tokens > maxInputTokens) {
            log.warn("Rejected oversized input: {} tokens > cap {}", tokens, maxInputTokens);
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Input is %d tokens, over the temporary cap of %d.".formatted(tokens, maxInputTokens));
        }
        return tokens;
    }
}
