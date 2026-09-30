package com.meridian.poc.assistant;

import com.meridian.poc.common.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** One customer conversation: a small state machine plus the transcript shown in the chat UI. */
public final class Conversation {
    public enum Step { IDLE, SELECT_TXN, SELECT_REASON, ASK_POSSESSION, CONFIRM }

    public final String id, sub;
    public final Instant createdAt = Instant.now();
    public volatile Step step = Step.IDLE;
    /** True while no EU model deployment is reachable: buttons and approved copy only (design §12). */
    public volatile boolean guided;
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();

    // Working context for the dispute being captured (references only; amounts kept for display binding)
    public String cardRef, cardDisplay;
    public List<Map<String, Object>> txnOptions = new ArrayList<>();
    public Map<String, Object> selectedTxn;
    public String reasonCode;
    public Boolean cardInPossession;

    public Conversation(String id, String sub) { this.id = id; this.sub = sub; }

    public synchronized Map<String, Object> add(String role, String text, Map<String, Object> card) {
        Map<String, Object> m = Json.obj("id", seq.incrementAndGet(), "role", role, "text", text, "ts", Instant.now().toString());
        if (card != null) m.put("card", card);
        messages.add(m);
        return m;
    }

    public synchronized List<Map<String, Object>> messagesAfter(int afterId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : messages) if (((Number) m.get("id")).intValue() > afterId) out.add(m);
        return out;
    }

    public void resetDraft() {
        step = Step.IDLE;
        txnOptions = new ArrayList<>();
        selectedTxn = null;
        reasonCode = null;
        cardInPossession = null;
    }
}
