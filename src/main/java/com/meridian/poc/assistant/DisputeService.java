package com.meridian.poc.assistant;

import com.meridian.poc.Config;
import com.meridian.poc.common.Http.ApiException;
import com.meridian.poc.common.Http.Response;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The dispute saga (design §7): <b>record → Salesforce case → core registration</b>, with an outbox
 * for everything that cannot finish inside the customer's time budget.
 *
 * <p>Idempotency layers implemented here and downstream:
 * (1) client-generated {@code disputeRequestId}; (2) this service's idempotency store (same key + same
 * payload → same dispute; same key + different payload → 422); (3) Salesforce upsert on external ID;
 * (4) façade read-before-write on the client reference; (5) one open dispute per transaction.</p>
 */
public final class DisputeService {
    /** Who is calling the façade: the customer (delegated token) or the saga continuing on its own. */
    public record CustomerAuth(String at1, Map<String, Object> claims) {}

    @FunctionalInterface
    public interface CustomerNotifier { void notify(Dispute d, String text, boolean push); }

    public record Draft(String cardRef, String cardDisplay, String txnRef, String merchant, String amount, String currency,
                        String txnDate, String reasonCode, String reasonLabel, Boolean cardInPossession) {
        String hash() {
            return sha256(cardRef + "|" + txnRef + "|" + reasonCode + "|" + cardInPossession);
        }
    }

    public record SubmitResult(Dispute dispute, boolean replayed, boolean existingDispute) {}

    private record IdemEntry(String payloadHash, String disputeId) {}

    private final Config cfg;
    private final FacadeClient facade;
    private final SalesforceClient salesforce;
    private final TokenBroker tokens;
    private final AuditLog audit;
    private final CustomerNotifier notifier;
    private final CircuitBreaker coreBreaker;
    private final RetryPolicy syncRetry = new RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(1));
    private final RetryPolicy singleAttempt = new RetryPolicy(1, Duration.ZERO, Duration.ZERO);

    private final Map<String, Dispute> disputes = new ConcurrentHashMap<>();
    private final Map<String, IdemEntry> idempotency = new ConcurrentHashMap<>();
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final Set<String> outbox = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outbox-worker");
        t.setDaemon(true);
        return t;
    });
    private final SecureRandom rnd = new SecureRandom();

    public DisputeService(Config cfg, FacadeClient facade, SalesforceClient salesforce, TokenBroker tokens,
                          AuditLog audit, CustomerNotifier notifier) {
        this.cfg = cfg; this.facade = facade; this.salesforce = salesforce; this.tokens = tokens;
        this.audit = audit; this.notifier = notifier;
        this.coreBreaker = new CircuitBreaker("core-banking", cfg.breakerWindow, cfg.breakerMinCalls,
                cfg.breakerFailureRate, cfg.breakerOpenFor);
    }

    public void start() {
        long tick = cfg.outboxTick.toMillis();
        worker.scheduleWithFixedDelay(this::drainOutbox, tick, tick, TimeUnit.MILLISECONDS);
    }

    public void stop() { worker.shutdownNow(); }

    // =================================================================== submit (customer present)
    public SubmitResult submit(String sub, String conversationId, String requestId, Draft draft, CustomerAuth auth) {
        Dispute d;
        synchronized (keyLocks.computeIfAbsent(requestId, k -> new Object())) {
            IdemEntry prior = idempotency.get(requestId);
            if (prior != null) {
                if (!prior.payloadHash.equals(draft.hash()))
                    throw new ApiException(Response.problem(422, "idempotency-key-reuse",
                            "disputeRequestId was already used for a different dispute", null));
                Dispute existing = disputes.get(prior.disputeId);
                Log.info("SAGA", "disputeRequestId %s already processed -> returning %s (no new dispute)", requestId, existing.id);
                audit.append("DISPUTE_REPLAYED", conversationId, sub, Json.obj("requestId", requestId, "disputeId", existing.id));
                return new SubmitResult(existing, true, false);
            }
            Dispute open = disputes.values().stream()
                    .filter(x -> x.sub.equals(sub) && x.txnRef.equals(draft.txnRef()) && x.isActive())
                    .findFirst().orElse(null);
            if (open != null) {
                idempotency.put(requestId, new IdemEntry(draft.hash(), open.id));
                return new SubmitResult(open, false, true);
            }
            // "One transaction": dispute row + idempotency row + outbox row are written together.
            d = new Dispute(newDisputeId(), sub, conversationId, requestId, draft.cardRef(), draft.cardDisplay(),
                    draft.txnRef(), draft.merchant(), draft.amount(), draft.currency(), draft.txnDate(), draft.reasonCode(),
                    draft.reasonLabel(), draft.cardInPossession(), Json.str(auth.claims(), "jti"), Json.str(auth.claims(), "acr"));
            synchronized (this) {
                disputes.put(d.id, d);
                idempotency.put(requestId, new IdemEntry(draft.hash(), d.id));
                outbox.add(d.id);
            }
        }
        Log.info("SAGA", "%s RECEIVED for %s (%s) - reference issued before any downstream call", d.id, d.txnRef, d.reasonCode);
        audit.append("DISPUTE_RECEIVED", conversationId, sub, Json.obj("disputeId", d.id, "txnRef", d.txnRef,
                "reason", d.reasonCode, "notifiedAt", d.notifiedAt.toString(), "acr", d.acrAtConfirmation));

        // Synchronous attempt inside the customer-facing budget; whatever is left goes to the outbox.
        d.lock.lock();
        try {
            advance(d, Instant.now().plus(cfg.userBudget), auth, syncRetry, false);
        } finally {
            d.lock.unlock();
        }
        return new SubmitResult(d, false, false);
    }

    // =================================================================== the saga step machine
    private void advance(Dispute d, Instant deadline, CustomerAuth auth, RetryPolicy policy, boolean async) {
        State before = State.of(d);
        // Step 1: Salesforce case (upsert by external id -> never duplicates)
        if (d.caseId == null) {
            try {
                Map<String, Object> r = policy.execute("Salesforce upsert " + d.id,
                        () -> salesforce.upsertCase(d.id, caseFields(d, null)), deadline);
                d.caseId = Json.str(r, "id");
                try {
                    d.caseNumber = Json.str(salesforce.getCase(d.id), "CaseNumber");
                } catch (RemoteError ignored) {
                    d.caseNumber = d.caseId; // display fallback; the case exists either way
                }
                transition(d, Dispute.State.CASE_OPEN, "Salesforce case " + d.caseNumber + " created");
            } catch (RemoteError e) {
                failed(d, e, "Salesforce");
                return;
            }
        }
        // Step 2: core registration via the bank façade (Idempotency-Key = dispute id)
        if (d.coreRef == null) {
            try {
                Map<String, Object> r = policy.execute("core registration " + d.id,
                        () -> coreBreaker.call(() -> registerInCore(d, auth)), deadline);
                d.coreRef = Json.str(r, "coreDisputeRef");
                d.casePatchPending = true;
                String how = Boolean.TRUE.equals(r.get("recoveredFromPriorAttempt"))
                        ? " (recovered: an earlier timed-out attempt had already landed)" : "";
                transition(d, Dispute.State.CORE_REGISTERED, "registered in core as " + d.coreRef + how);
            } catch (RemoteError e) {
                if (e.status == 409) {
                    d.coreRef = Json.str(e.body, "existingCoreDisputeRef");
                    d.casePatchPending = true;
                    transition(d, Dispute.State.CORE_REGISTERED, "linked to existing core dispute " + d.coreRef);
                } else if (!e.retryable) {
                    d.lastError = e.getMessage();
                    toManualReview(d, "non-retryable error: " + e.getMessage());
                    return;
                } else {
                    if (d.state != Dispute.State.CORE_PENDING)
                        transition(d, Dispute.State.CORE_PENDING, "core registration pending: " + e.getMessage());
                    failed(d, e, "core");
                    return;
                }
            }
        }
        // Step 3: patch the case with the core reference (same upsert, now an update)
        if (d.casePatchPending) {
            try {
                singleAttempt.execute("Salesforce patch " + d.id,
                        () -> salesforce.upsertCase(d.id, caseFields(d, "Registered in core banking")), deadline);
                d.casePatchPending = false;
            } catch (RemoteError e) {
                failed(d, e, "Salesforce patch");
                return;
            }
        }
        if (!d.needsWork()) {
            outbox.remove(d.id);
            d.lastError = null;
            if (async && !before.equals(State.of(d))) {
                notifier.notify(d, "Update on dispute " + d.id + ": it's now registered with our disputes team (case "
                        + d.caseNumber + "). There's nothing more you need to do.", true);
            }
        }
    }

    private Map<String, Object> registerInCore(Dispute d, CustomerAuth auth) throws RemoteError {
        Map<String, Object> body = Json.obj("cardRef", d.cardRef, "txnRef", d.txnRef, "reasonCode", d.reasonCode,
                "cardInPossession", d.cardInPossession, "notifiedAt", d.notifiedAt.toString());
        String token;
        if (auth != null) {
            token = tokens.delegated(auth.at1(), auth.claims(), "assistant.disputes.write");
        } else {
            // Customer long gone: continue the confirmed instruction with a narrowly-scoped service token.
            token = tokens.service();
            body.put("onBehalfOf", d.sub);
            body.put("consent", Json.obj("customerTokenId", d.customerTokenId, "confirmedAt", d.notifiedAt.toString(),
                    "acr", d.acrAtConfirmation));
        }
        return facade.registerDispute(token, d.id, body);
    }

    private Map<String, Object> caseFields(Dispute d, String integrationStatus) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("Subject", "Card dispute - " + d.merchant);
        f.put("Origin", "Digital Assistant");
        f.put("RecordType", "Card_Dispute");
        f.put("Reason", d.reasonLabel);
        f.put("Customer_Pseudonym__c", d.sub);          // Salesforce resolves the customer bank-side
        f.put("Card_Ref__c", d.cardRef);                 // masked/opaque ref only - never a PAN
        f.put("Transaction_Ref__c", d.txnRef);
        f.put("Disputed_Amount__c", d.amount);
        f.put("Currency__c", d.currency);
        f.put("Customer_Notified_At__c", d.notifiedAt.toString()); // PSD2 clock evidence
        f.put("Card_In_Possession__c", d.cardInPossession);
        if (d.coreRef != null) f.put("Core_Dispute_Ref__c", d.coreRef);
        if (integrationStatus != null) f.put("Integration_Status__c", integrationStatus);
        return f;
    }

    private void failed(Dispute d, RemoteError e, String step) {
        d.lastError = e.getMessage();
        d.attempts++;
        Duration delay = RetryPolicy.backoff(Math.min(d.attempts, 10), Duration.ofSeconds(1), cfg.asyncBackoffCap);
        d.nextAttemptAt = Instant.now().plus(delay);
        Log.warn("OUTBOX", "%s: %s step deferred (%s) - next attempt in %d ms (attempt %d)", d.id, step, e.getMessage(), delay.toMillis(), d.attempts);
        if (Instant.now().isAfter(d.notifiedAt.plus(cfg.manualReviewAfter))) toManualReview(d, "retries exhausted: " + e.getMessage());
    }

    private void toManualReview(Dispute d, String why) {
        transition(d, Dispute.State.MANUAL_REVIEW, why);
        outbox.remove(d.id);
        Log.warn("OUTBOX", "ALERT %s moved to MANUAL_REVIEW (%s) - routed to disputes back-office queue", d.id, why);
        if (d.caseId != null) {
            try {
                salesforce.upsertCase(d.id, Json.obj("Status", "Escalated", "Integration_Status__c", "Manual registration required"));
            } catch (RemoteError ignored) { /* the alert above is the backstop */ }
        }
        notifier.notify(d, "Update on dispute " + d.id + ": a colleague is completing its registration manually. It is still dated "
                + fmtDate(d.notifiedAt) + ", and you don't need to do anything.", true);
    }

    private void transition(Dispute d, Dispute.State to, String note) {
        Dispute.State from = d.state;
        d.state = to;
        d.note(to.name(), note);
        Log.info("SAGA", "%s %s -> %s: %s", d.id, from, to, note);
        audit.append("DISPUTE_STATE", d.conversationId, d.sub, Json.obj("disputeId", d.id, "from", from, "to", to, "note", note));
    }

    // =================================================================== outbox worker (customer absent)
    private void drainOutbox() {
        try {
            for (String id : new ArrayList<>(outbox)) {
                Dispute d = disputes.get(id);
                if (d == null || Instant.now().isBefore(d.nextAttemptAt) || !d.lock.tryLock()) continue;
                try {
                    Log.info("OUTBOX", "%s: retrying (state %s, attempt %d)", d.id, d.state, d.attempts + 1);
                    advance(d, Instant.now().plusSeconds(5), null, singleAttempt, true);
                } finally {
                    d.lock.unlock();
                }
            }
        } catch (Throwable t) {
            Log.warn("OUTBOX", "worker error: %s", t);
        }
    }

    // =================================================================== status events from Salesforce
    /** @return "applied", "duplicate", "stale" or "unknown" */
    private final Set<String> seenEvents = ConcurrentHashMap.newKeySet();

    public String onCaseStatusEvent(Map<String, Object> ev) {
        String eventId = Json.str(ev, "eventUuid");
        if (!seenEvents.add(eventId)) {
            Log.info("WEBHOOK", "event %s already processed -> ignored (idempotent consumer)", eventId);
            return "duplicate";
        }
        Dispute d = disputes.get(Json.str(ev, "disputeRef"));
        if (d == null) return "unknown";
        long version = ((Number) ev.get("caseVersion")).longValue();
        synchronized (d) {
            if (version <= d.caseVersion) {
                Log.info("WEBHOOK", "event v%d for %s older than v%d -> ignored (out of order)", version, d.id, d.caseVersion);
                return "stale";
            }
            d.caseVersion = version;
        }
        String status = Json.str(ev, "status");
        Dispute.State to = switch (status) {
            case "In Review" -> Dispute.State.IN_REVIEW;
            case "Closed - Accepted" -> Dispute.State.RESOLVED_ACCEPTED;
            case "Closed - Rejected" -> Dispute.State.RESOLVED_REJECTED;
            default -> null;
        };
        if (to == null) return "applied";
        transition(d, to, "Salesforce case status '" + status + "' (v" + version + ")");
        String text = switch (to) {
            case IN_REVIEW -> "Update on dispute " + d.id + ": our disputes team is now reviewing your case.";
            case RESOLVED_ACCEPTED -> "Update on dispute " + d.id + ": the disputes team has accepted your claim. You'll receive the details in writing.";
            default -> "Update on dispute " + d.id + ": the disputes team has completed its review. You'll receive the details in writing.";
        };
        notifier.notify(d, text, true);
        return "applied";
    }

    // =================================================================== queries
    public Dispute get(String id) { return disputes.get(id); }

    public List<Dispute> forSubject(String sub) {
        return disputes.values().stream().filter(d -> d.sub.equals(sub)).toList();
    }

    public List<Object> allViews() {
        List<Object> out = new ArrayList<>();
        disputes.values().stream().sorted((a, b) -> a.notifiedAt.compareTo(b.notifiedAt)).forEach(d -> out.add(d.view()));
        return out;
    }

    public List<String> outboxIds() { return new ArrayList<>(outbox); }

    public String breakerState() { return coreBreaker.state().name(); }

    // =================================================================== helpers
    private String newDisputeId() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("DSP-");
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    static String fmtDate(Instant i) {
        return DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC).format(i);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compact state snapshot used to detect whether an async pass changed anything. */
    private record State(Dispute.State s, String caseId, String coreRef) {
        static State of(Dispute d) { return new State(d.state, d.caseId, d.coreRef); }
    }
}
