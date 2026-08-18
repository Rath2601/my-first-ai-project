package com.rath.first.project.dto;

/**
 * The exact shape of data we want back when we ask the AI to triage a ticket.
 *
 * Instead of getting a paragraph of text from the model, we want structured
 * fields we can use in code. Spring AI reads this record, describes it to the
 * model as a JSON template ("give me a category, a priority, and a summary"),
 * and then converts the model's JSON reply back into a real TicketTriage object.
 *
 * A "record" is just a compact, read-only data holder — perfect for this.
 *
 * @param category  a short label for the type of issue, e.g. "Billing"
 * @param priority  how urgent it is (see {@link Priority})
 * @param summary   a one-line plain-English summary of the ticket
 */
public record TicketTriage(
        String category,
        Priority priority,
        String summary) {}
