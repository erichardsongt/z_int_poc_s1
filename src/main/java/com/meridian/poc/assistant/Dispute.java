package com.meridian.poc.assistant;

import com.meridian.poc.common.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Dispute working record held by the data plane until Salesforce and core banking both have it
 * (design §6: purged 30 days after core registration; Salesforce is the system of record).
 */
public final class Dispute {
    public enum State {
        RECEIVED,           // durably recorded, reference issued, nothing downstream yet
        CASE_OPEN,          // Salesforce case exists
        CORE_PENDING,       // core registration failing; async retries in progress
        CORE_REGISTERED,    // registered in core; case patched with core ref
        IN_REVIEW,          // driven by Salesforce status events
        RESOLVED_ACCEPTED,
        RESOLVED_REJECTED,
        MANUAL_REVIEW       // retries exhausted -> disputes back-office queue
    }

    public final String id, sub, conversationId, requestId, cardRef, cardDisplay, txnRef, merchant, amount, currency,
            txnDate, reasonCode, reasonLabel;
    public final Boolean cardInPossession;
    public final Instant notifiedAt;
    // Evidence of the customer's confirmed instruction, used for asynchronous continuation.
    public final String customerTokenId, acrAtConfirmation;

    public volatile State state = State.RECEIVED;
    public volatile String caseId, caseNumber, coreRef, lastError;
    public volatile long caseVersion;
    public volatile int attempts;
    public volatile Instant nextAttemptAt = Instant.EPOCH;
    public volatile boolean casePatchPending;
    public final List<Map<String, Object>> history = new ArrayList<>();
    final ReentrantLock lock = new ReentrantLock();

    public Dispute(String id, String sub, String conversationId, String requestId, String cardRef, String cardDisplay,
                   String txnRef, String merchant, String amount, String currency, String txnDate, String reasonCode,
                   String reasonLabel, Boolean cardInPossession, String customerTokenId, String acrAtConfirmation) {
        this.id = id; this.sub = sub; this.conversationId = conversationId; this.requestId = requestId;
        this.cardRef = cardRef; this.cardDisplay = cardDisplay; this.txnRef = txnRef; this.merchant = merchant;
        this.amount = amount; this.currency = currency; this.txnDate = txnDate; this.reasonCode = reasonCode;
        this.reasonLabel = reasonLabel; this.cardInPossession = cardInPossession; this.notifiedAt = Instant.now();
        this.customerTokenId = customerTokenId; this.acrAtConfirmation = acrAtConfirmation;
        note("RECEIVED", "dispute recorded; customer notification time fixed");
    }

    public synchronized void note(String state, String text) {
        history.add(Json.obj("ts", Instant.now().toString(), "state", state, "note", text));
    }

    public boolean isActive() { return state != State.RESOLVED_ACCEPTED && state != State.RESOLVED_REJECTED; }

    public boolean needsWork() {
        return state != State.MANUAL_REVIEW && (caseId == null || coreRef == null || casePatchPending);
    }

    /** Customer-safe view (API responses and the demo console). */
    public synchronized Map<String, Object> view() {
        return Json.obj("disputeId", id, "state", state, "notifiedAt", notifiedAt.toString(),
                "transaction", Json.obj("txnRef", txnRef, "merchant", merchant, "date", txnDate,
                        "amount", Json.obj("value", amount, "currency", currency)),
                "reason", reasonCode, "caseNumber", caseNumber, "caseId", caseId, "coreRef", coreRef,
                "attempts", attempts, "lastError", lastError, "nextAttemptAt", needsWork() ? nextAttemptAt.toString() : null,
                "history", new ArrayList<>(history));
    }
}
