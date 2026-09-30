package com.meridian.poc.llm;

import java.util.Locale;
import java.util.Map;

/**
 * The "model" served by {@link MockModelEndpoint} (the data plane never calls this class directly; it goes
 * through the LLM gateway over HTTP). Stand-in for the EU-hosted LLM behind the LLM gateway. In the design the model does two jobs only:
 * understand the customer (intent + details) and phrase replies. It never decides which tool runs and
 * never produces numbers. This stub is deterministic keyword matching so the demo is repeatable and
 * needs no API key; its outputs use {{SLOT}} placeholders exactly as a real model would be prompted to.
 */
public final class LanguageModelStub {
    public enum Intent { DISPUTE, DISPUTE_STATUS, AGENT, GREETING, UNKNOWN }

    public Intent classify(String redactedText) {
        String t = redactedText.toLowerCase(Locale.ROOT);
        if (containsAny(t, "status", "any news", "update on", "what happened", "my dispute", "existing", "open dispute",
                "show", "list", "check on", "progress", "where is", "where are")) return Intent.DISPUTE_STATUS;
        if (containsAny(t, "recognis", "recogniz", "dispute", "charge", "fraud", "didn't make", "did not make", "chargeback", "wrong payment"))
            return Intent.DISPUTE;
        if (containsAny(t, "agent", "human", "person", "someone")) return Intent.AGENT;
        if (containsAny(t, "hello", "hi ", "hey")) return Intent.GREETING;
        return Intent.UNKNOWN;
    }

    /** Extract a dispute reason from free text; null if unclear (the UI then offers the list). */
    public String extractReason(String redactedText) {
        String t = redactedText.toLowerCase(Locale.ROOT);
        if (containsAny(t, "didn't make", "did not make", "not me", "unauthori", "fraud", "don't recognise", "don't recognize")) return "UNAUTHORISED";
        if (containsAny(t, "never arrived", "not received", "didn't receive", "didn't arrive")) return "NOT_RECEIVED";
        if (containsAny(t, "twice", "duplicate", "double")) return "DUPLICATE";
        if (containsAny(t, "wrong amount", "incorrect amount", "overcharged")) return "INCORRECT_AMOUNT";
        if (containsAny(t, "cancelled", "canceled", "subscription")) return "CANCELLED_RECURRING";
        return null;
    }

    /** "Generated" phrasing – placeholders only; bound later by the data plane's Guardrails.bind. */
    public String phrase(String key) {
        return PHRASES.getOrDefault(key, PHRASES.get("fallback"));
    }

    private static final Map<String, String> PHRASES = Map.of(
            "greeting", "Hi, I'm Meridian's digital assistant. I'm an AI system, and I can help you dispute a card payment or check on a dispute. You can ask for a colleague at any time.",
            "txn_intro", "Here are the recent payments on your {{CARD}}. Which one would you like to dispute?",
            "ask_reason", "Got it: {{MERCHANT}} on {{DATE}}. What's wrong with this payment?",
            "ask_possession", "Thanks. Is your card still in your possession?",
            "pan_warning", "For your security I've removed a card number from your message. Please never share full card numbers in chat.",
            "agent", "I'd hand you to a colleague here, together with this conversation's context. (Agent handoff isn't part of this prototype slice.)",
            "fallback", "I can help you dispute a card payment or check on an existing dispute. What would you like to do?",
            "cancelled", "No problem, I haven't submitted anything. Is there anything else I can help with?");

    private static boolean containsAny(String t, String... needles) {
        for (String n : needles) if (t.contains(n)) return true;
        return false;
    }
}
