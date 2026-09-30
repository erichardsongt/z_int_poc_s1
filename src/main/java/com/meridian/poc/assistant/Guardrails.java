package com.meridian.poc.assistant;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two of the design's LLM-safety controls (§10), in miniature:
 * <ol>
 *   <li><b>PAN redaction</b> before anything reaches the model, the transcript or the audit log
 *       (server-side backstop to the SDK's client-side masking);</li>
 *   <li><b>numbers are never generated</b>: text coming from the language model may reference values only
 *       through {@code {{SLOT}}} placeholders; a literal amount in model output is rejected, and slots are
 *       bound from system data after generation.</li>
 * </ol>
 */
public final class Guardrails {
    private Guardrails() {}

    public record Redaction(String text, boolean panFound) {}

    private static final Pattern CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
    private static final Pattern LITERAL_AMOUNT = Pattern.compile("(€|EUR|\\$|£)\\s?\\d|\\d+[.,]\\d{2}\\b");
    private static final Pattern SLOT = Pattern.compile("\\{\\{([A-Z_0-9]+)}}");

    public static Redaction redactPan(String text) {
        if (text == null) return new Redaction(null, false);
        Matcher m = CANDIDATE.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            if (luhn(digits)) {
                found = true;
                m.appendReplacement(sb, "[card number removed]");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return new Redaction(sb.toString(), found);
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return digits.length() >= 13 && sum % 10 == 0;
    }

    /** Validate model output (no literal amounts), then bind slots from trusted system values. */
    public static String bind(String modelOutput, Map<String, String> slots) {
        if (LITERAL_AMOUNT.matcher(modelOutput).find())
            throw new IllegalStateException("Guardrail: model output contains a literal amount; blocked");
        Matcher m = SLOT.matcher(modelOutput);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = slots.get(m.group(1));
            if (v == null) throw new IllegalStateException("Guardrail: unbound slot " + m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String money(String value, String currency) {
        return ("EUR".equals(currency) ? "€" : currency + " ") + value;
    }
}
