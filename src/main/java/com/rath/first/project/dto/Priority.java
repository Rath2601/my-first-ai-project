package com.rath.first.project.dto;

/**
 * The fixed set of priority levels a support ticket can have.
 *
 * Because this is an enum, the AI is only allowed to pick ONE of these four
 * values — it can't invent something like "SUPER_URGENT". Spring AI includes
 * this list of allowed values in the instructions it sends to the model, which
 * is what keeps the AI's answer clean and predictable.
 */
public enum Priority {
    LOW, MEDIUM, HIGH, URGENT
}
