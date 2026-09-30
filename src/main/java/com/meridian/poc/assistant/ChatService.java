package com.meridian.poc.assistant;

import com.meridian.poc.Config;
import com.meridian.poc.common.Http.ApiException;
import com.meridian.poc.common.Http.Response;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversation orchestrator (design §4/§10): a deterministic state machine for dispute capture.
 * The language model only classifies and phrases; this class decides which tool runs, under which
 * scope, and builds the confirmation card deterministically (never model-generated).
 */
public final class ChatService implements DisputeService.CustomerNotifier {
    static final Map<String, String> REASONS = new LinkedHashMap<>();
    static {
        REASONS.put("UNAUTHORISED", "I didn't make this payment");
        REASONS.put("NOT_RECEIVED", "I paid but didn't receive the goods or service");
        REASONS.put("DUPLICATE", "I was charged twice");
        REASONS.put("INCORRECT_AMOUNT", "The amount is wrong");
        REASONS.put("CANCELLED_RECURRING", "I cancelled this subscription");
    }

    private final Config cfg;
    /**
     * Bank-approved copy used when no EU model deployment is reachable (guided mode, design §12).
     * Same keys and {{SLOT}} placeholders as the model's phrasing, so the rest of the flow is unchanged.
     */
    static final Map<String, String> GUIDED_COPY = Map.of(
            "greeting", "Hi, I'm Meridian's digital assistant, an AI system. I'm running in guided mode right now, so please choose one of the options below.",
            "guided_menu", "I can't read free text at the moment, but I can still help. Please choose an option:",
            "txn_intro", "Recent payments on your {{CARD}}. Select the payment you want to dispute:",
            "ask_reason", "{{MERCHANT}} on {{DATE}}. Select what's wrong with this payment:",
            "ask_possession", "Is your card still in your possession?",
            "pan_warning", "For your security, a card number was removed from your message. Please never share full card numbers in chat.",
            "agent", "A colleague can help with this. (Agent handoff isn't part of this prototype slice.)",
            "fallback", "Please choose one of the options below.",
            "cancelled", "Nothing was submitted. Choose an option below if you need anything else.");

    private final LlmGateway llm;
    private final FacadeClient facade;
    private final TokenBroker tokens;
    private final AuditLog audit;
    private DisputeService disputes;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public ChatService(Config cfg, FacadeClient facade, TokenBroker tokens, AuditLog audit, LlmGateway llm) {
        this.cfg = cfg; this.facade = facade; this.tokens = tokens; this.audit = audit; this.llm = llm;
    }

    public void setDisputes(DisputeService d) { this.disputes = d; }

    // ------------------------------------------------------------------ lifecycle
    public Conversation start(String sub) {
        Conversation c = new Conversation("CNV-" + UUID.randomUUID().toString().substring(0, 8), sub);
        conversations.put(c.id, c);
        String greeting = phrase(c, "greeting");             // EU AI Act Art. 50(1) disclosure up front, in both modes
        c.add("assistant", greeting, c.guided ? menuCard() : null);
        List<Dispute> existing = disputes.forSubject(sub);
        audit.append("CONVERSATION_STARTED", c.id, sub, Json.obj("aiDisclosureShown", true, "existingDisputes", existing.size()));
        if (!existing.isEmpty()) {
            // Pull-on-open (design §7): the customer sees current dispute status as soon as the assistant opens,
            // even if a push notification or status event was missed.
            c.add("assistant", existing.size() == 1 ? "You have 1 dispute with us. Here's where it stands:"
                    : "You have " + existing.size() + " disputes with us. Here's where they stand:", disputesCard(existing));
        }
        return c;
    }

    public Conversation get(String id, String sub) {
        Conversation c = conversations.get(id);
        if (c == null || !c.sub.equals(sub)) // 404 rather than 403: don't reveal other customers' conversations
            throw new ApiException(Response.problem(404, "not-found", "Conversation not found", null));
        return c;
    }

    // ------------------------------------------------------------------ one customer turn
    public List<Map<String, Object>> handle(Conversation c, Map<String, Object> body, String at1, Map<String, Object> claims) {
        synchronized (c) { // one turn at a time per conversation (double-taps queue up and replay)
            int mark = c.messagesAfter(0).size();
            // In guided mode every turn re-probes the model (cheap while its circuit breaker is open),
            // so the conversation returns to AI mode as soon as an EU deployment is back.
            if (c.guided) phrase(c, "fallback");
            String action = Json.str(body, "action");
            if (action != null) handleAction(c, action, body, at1, claims);
            else handleText(c, Json.str(body, "text"), at1, claims);
            return c.messagesAfter(mark);
        }
    }

    private void handleText(Conversation c, String text, String at1, Map<String, Object> claims) {
        if (text == null || text.isBlank()) throw new ApiException(Response.problem(400, "empty-message", "text or action required", null));
        Guardrails.Redaction r = Guardrails.redactPan(text);
        c.add("customer", r.text(), null);
        LlmGateway.Understanding u = understand(c, r.text());   // null = no model available -> guided mode
        String intent = u == null ? "UNAVAILABLE" : u.intent();
        audit.append("CUSTOMER_TURN", c.id, c.sub, Json.obj("intent", intent, "step", c.step, "chars", r.text().length(),
                "mode", c.guided ? "guided" : "ai"));
        if (r.panFound()) {
            audit.append("GUARDRAIL_PAN_REDACTED", c.id, c.sub, Json.obj("where", "inbound customer message"));
            Log.warn("ASSIST", "card number detected in customer text -> redacted before model, transcript and audit");
            say(c, "pan_warning", Map.of());
        }
        String t = r.text().toLowerCase(Locale.ROOT);
        switch (c.step) {
            case SELECT_TXN -> {
                Map<String, Object> m = matchTxn(c, t);
                if (m != null) selectTxn(c, Json.str(m, "txnRef"));
                else c.add("assistant", "Please pick the payment from the list above, or tell me the merchant name.", null);
            }
            case SELECT_REASON -> {
                String reason = u == null ? null : u.reason();
                if (reason != null) selectReason(c, reason);
                else c.add("assistant", c.guided ? "Please select one of these options:" : "Which of these best describes the problem?", reasonCard());
            }
            case ASK_POSSESSION -> {
                if (t.matches(".*\\b(yes|yep|i do|have it)\\b.*")) answerPossession(c, true);
                else if (t.matches(".*\\b(no|lost|stolen)\\b.*")) answerPossession(c, false);
                else c.add("assistant", "Is your card still with you? Yes or no is fine.", possessionCard());
            }
            case CONFIRM -> {
                if (t.matches(".*\\b(yes|confirm|submit|go ahead)\\b.*"))
                    confirm(c, deterministicRequestId(c), at1, claims);   // text path: key derived from the draft
                else if (t.matches(".*\\b(no|cancel|stop)\\b.*")) cancel(c);
                else c.add("assistant", "Shall I submit this dispute? Please confirm or cancel below.", confirmCard(c));
            }
            case IDLE -> {
                switch (intent) {
                    case "DISPUTE" -> startDispute(c, at1, claims);
                    case "DISPUTE_STATUS" -> statusReply(c);
                    case "AGENT" -> say(c, "agent", Map.of());
                    case "UNAVAILABLE" -> c.add("assistant", GUIDED_COPY.get("guided_menu"), menuCard());
                    default -> say(c, "fallback", Map.of(), c.guided ? menuCard() : null);
                }
            }
        }
    }

    private void handleAction(Conversation c, String action, Map<String, Object> body, String at1, Map<String, Object> claims) {
        String value = Json.str(body, "value");
        audit.append("CUSTOMER_ACTION", c.id, c.sub, Json.obj("action", action, "step", c.step));
        switch (action) {
            case "start_dispute" -> startDispute(c, at1, claims);
            case "agent" -> say(c, "agent", Map.of());
            case "menu" -> {   // guided-mode menu: each button maps to a deterministic flow
                switch (String.valueOf(value)) {
                    case "start_dispute" -> startDispute(c, at1, claims);
                    case "status" -> statusReply(c);
                    case "agent" -> say(c, "agent", Map.of());
                    default -> throw new ApiException(Response.problem(400, "unknown-menu-item", "Unknown menu item", value));
                }
            }
            case "select_txn" -> { requireStep(c, Conversation.Step.SELECT_TXN); selectTxn(c, value); }
            case "select_reason" -> { requireStep(c, Conversation.Step.SELECT_REASON); selectReason(c, value); }
            case "possession" -> { requireStep(c, Conversation.Step.ASK_POSSESSION); answerPossession(c, "yes".equals(value)); }
            case "confirm" -> {
                String requestId = Json.str(body, "disputeRequestId");
                if (requestId == null) throw new ApiException(Response.problem(400, "request-id-required", "disputeRequestId required", null));
                confirm(c, requestId, at1, claims);
            }
            case "cancel" -> cancel(c);
            case "status" -> statusReply(c);
            default -> throw new ApiException(Response.problem(400, "unknown-action", "Unknown action", action));
        }
    }

    // ------------------------------------------------------------------ dispute capture steps
    private void startDispute(Conversation c, String at1, Map<String, Object> claims) {
        c.resetDraft();
        try {
            String at2 = tokens.delegated(at1, claims, "assistant.transactions.read");
            List<Object> cards = Json.list(facade.cards(at2), "cards");
            @SuppressWarnings("unchecked") Map<String, Object> card = (Map<String, Object>) cards.get(0);
            c.cardRef = Json.str(card, "cardRef");
            c.cardDisplay = Json.str(card, "product") + " " + Json.str(card, "display");
            Map<String, Object> resp = facade.transactions(at2, c.cardRef);
            List<Map<String, Object>> options = new ArrayList<>();
            for (Object o : Json.list(resp, "transactions")) {
                @SuppressWarnings("unchecked") Map<String, Object> tx = (Map<String, Object>) o;
                options.add(tx);
            }
            c.txnOptions = options;
            audit.append("TOOL_CALL", c.id, c.sub, Json.obj("tool", "facade.transactions", "cardRef", c.cardRef, "rows", options.size()));
            c.step = Conversation.Step.SELECT_TXN;
            say(c, "txn_intro", Map.of("CARD", c.cardDisplay), txnCard(c));
        } catch (RemoteError e) {
            audit.append("TOOL_ERROR", c.id, c.sub, Json.obj("tool", "facade.transactions", "error", e.getMessage()));
            c.add("assistant", "I can't reach your card payments right now. Please try again in a few minutes, or ask for a colleague.", null);
        }
    }

    private void selectTxn(Conversation c, String txnRef) {
        Map<String, Object> tx = c.txnOptions.stream().filter(o -> txnRef != null && txnRef.equals(o.get("txnRef"))).findFirst().orElse(null);
        if (tx == null) throw new ApiException(Response.problem(422, "unknown-transaction", "Transaction not in the list offered", null));
        if (!Boolean.TRUE.equals(tx.get("disputable"))) {
            c.add("assistant", "That payment is still pending, so it can't be disputed yet. If it's still wrong once it has posted, I can help then.", null);
            return;
        }
        Dispute open = openDisputeFor(c.sub, txnRef);
        if (open != null) {
            c.add("assistant", "You already have an open dispute for this payment: " + open.id + ". Here's its current status:",
                    disputesCard(List.of(open)));
            return;
        }
        c.selectedTxn = tx;
        c.step = Conversation.Step.SELECT_REASON;
        say(c, "ask_reason", Map.of("MERCHANT", Json.str(tx, "merchant"), "DATE", Json.str(tx, "date")), reasonCard());
    }

    private void selectReason(Conversation c, String reason) {
        if (!REASONS.containsKey(reason)) throw new ApiException(Response.problem(422, "unknown-reason", "Unknown reason", reason));
        c.reasonCode = reason;
        if ("UNAUTHORISED".equals(reason)) {
            c.step = Conversation.Step.ASK_POSSESSION;
            say(c, "ask_possession", Map.of(), possessionCard());
        } else {
            c.step = Conversation.Step.CONFIRM;
            c.add("assistant", "Please check the details below. I'll submit your dispute when you confirm.", confirmCard(c));
        }
    }

    private void answerPossession(Conversation c, boolean has) {
        c.cardInPossession = has;
        c.step = Conversation.Step.CONFIRM;
        String extra = has ? "" : " Because your card may be lost or stolen, a colleague will also contact you about blocking it.";
        c.add("assistant", "Please check the details below. I'll submit your dispute when you confirm." + extra, confirmCard(c));
    }

    private void confirm(Conversation c, String requestId, String at1, Map<String, Object> claims) {
        if (c.step != Conversation.Step.CONFIRM) {
            // A double-tap or network retry after the first confirm already completed: replay, don't re-create.
            Dispute prior = disputes.forSubject(c.sub).stream().filter(d -> d.requestId.equals(requestId)).findFirst().orElse(null);
            if (prior != null) {
                c.add("assistant", "Your dispute " + prior.id + " was already submitted. Nothing further was sent.", disputeCard(prior));
                audit.append("DISPUTE_REPLAYED", c.id, c.sub, Json.obj("requestId", requestId, "disputeId", prior.id));
                return;
            }
            throw new ApiException(Response.problem(409, "nothing-to-confirm", "No dispute is awaiting confirmation", null));
        }
        requireStepUp(c, claims);
        Map<String, Object> tx = c.selectedTxn;
        Map<String, Object> amount = Json.map(tx, "amount");
        DisputeService.Draft draft = new DisputeService.Draft(c.cardRef, c.cardDisplay, Json.str(tx, "txnRef"), Json.str(tx, "merchant"),
                Json.str(amount, "value"), Json.str(amount, "currency"), Json.str(tx, "date"), c.reasonCode,
                REASONS.get(c.reasonCode), c.cardInPossession);
        DisputeService.SubmitResult res = disputes.submit(c.sub, c.id, requestId, draft, new DisputeService.CustomerAuth(at1, claims));
        c.resetDraft();
        Dispute d = res.dispute();
        Map<String, String> slots = Map.of("REF", d.id, "CASE", String.valueOf(d.caseNumber), "DATE", DisputeService.fmtDate(d.notifiedAt));
        String text;
        if (res.existingDispute()) text = "There's already an open dispute for this payment: reference {{REF}}. I haven't created a second one.";
        else if (res.replayed()) text = "Your dispute {{REF}} was already submitted. Nothing further was sent.";
        else text = switch (d.state) {
            case CORE_REGISTERED -> "Your dispute is registered. Reference {{REF}}, case {{CASE}}. There's nothing more you need to do; I'll keep you updated here and by notification.";
            case MANUAL_REVIEW -> "I've recorded your dispute (reference {{REF}}), dated {{DATE}}. A colleague will complete its registration; you don't need to do anything.";
            default -> "I've recorded your dispute (reference {{REF}}), dated {{DATE}}. Our systems are slow to confirm it right now, so there's nothing more you need to do. I'll notify you as soon as it's confirmed.";
        };
        c.add("assistant", Guardrails.bind(text, slots), disputeCard(d));
    }

    /** RFC 9470: disputes need a recent strong authentication; otherwise challenge the app to step up. */
    private void requireStepUp(Conversation c, Map<String, Object> claims) {
        Object authTime = claims.get("auth_time");
        long age = authTime instanceof Number n ? Instant.now().getEpochSecond() - n.longValue() : Long.MAX_VALUE;
        if (!"high".equals(claims.get("acr")) || age > cfg.stepUpMaxAge.toSeconds()) {
            audit.append("STEP_UP_REQUIRED", c.id, c.sub, Json.obj("acr", claims.get("acr"), "authAgeSeconds", age));
            Log.info("ASSIST", "dispute confirmation needs step-up (acr=%s) -> 401 insufficient_user_authentication", claims.get("acr"));
            throw new ApiException(Response.problem(401, "step-up-required", "Stronger authentication required",
                            "Confirm it's you to submit a dispute", "acr_values", "high", "max_age", cfg.stepUpMaxAge.toSeconds())
                    .header("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\", error_description=\"A recent strong authentication is required\", acr_values=\"high\", max_age=\"" + cfg.stepUpMaxAge.toSeconds() + "\""));
        }
    }

    private void cancel(Conversation c) {
        c.resetDraft();
        say(c, "cancelled", Map.of(), c.guided ? menuCard() : null);
    }

    private void statusReply(Conversation c) {
        List<Dispute> mine = disputes.forSubject(c.sub);
        audit.append("TOOL_CALL", c.id, c.sub, Json.obj("tool", "disputes.list", "rows", mine.size()));
        if (mine.isEmpty()) { c.add("assistant", "You don't have any disputes with us at the moment.", null); return; }
        c.add("assistant", "Here's where your disputes stand:", disputesCard(mine));
    }

    private Map<String, Object> disputesCard(List<Dispute> list) {
        List<Object> items = new ArrayList<>();
        list.stream().sorted((a, b) -> a.notifiedAt.compareTo(b.notifiedAt)).forEach(d -> items.add(Json.obj(
                "disputeId", d.id, "merchant", d.merchant, "date", d.txnDate,
                "amount", Guardrails.money(d.amount, d.currency), "reason", d.reasonLabel,
                "state", d.state, "stateLabel", label(d.state), "caseNumber", d.caseNumber,
                "notifiedOn", DisputeService.fmtDate(d.notifiedAt))));
        return Json.obj("type", "disputes", "items", items);
    }

    private Dispute openDisputeFor(String sub, String txnRef) {
        return disputes.forSubject(sub).stream().filter(d -> d.txnRef.equals(txnRef) && d.isActive()).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ notifications from the saga / webhooks
    @Override
    public void notify(Dispute d, String text, boolean push) {
        Conversation c = conversations.get(d.conversationId);
        if (c != null) c.add("assistant", text, disputeCard(d));
        if (push) {
            // Only a generic prompt goes over push (lock screen); details stay inside the authenticated app.
            Log.info("PUSH", "Meridian push service -> %s: \"There's an update on your dispute\"", d.sub);
            audit.append("PUSH_SENT", d.conversationId, d.sub, Json.obj("disputeId", d.id, "template", "dispute_update"));
        }
    }

    // ------------------------------------------------------------------ rendering helpers
    private void say(Conversation c, String phraseKey, Map<String, String> slots) { say(c, phraseKey, slots, null); }

    private void say(Conversation c, String phraseKey, Map<String, String> slots, Map<String, Object> card) {
        String text = phrase(c, phraseKey);   // model phrasing, or approved guided copy if no model is reachable
        c.add("assistant", Guardrails.bind(text, slots), card);
    }

    // ------------------------------------------------------------------ model access with guided-mode fallback
    private String phrase(Conversation c, String key) {
        try {
            String t = llm.phrase(key);
            modelBack(c);
            return t;
        } catch (LlmGateway.Unavailable e) {
            enterGuided(c, e);
            return GUIDED_COPY.getOrDefault(key, GUIDED_COPY.get("fallback"));
        }
    }

    private LlmGateway.Understanding understand(Conversation c, String text) {
        try {
            LlmGateway.Understanding u = llm.understand(text);
            modelBack(c);
            return u;
        } catch (LlmGateway.Unavailable e) {
            enterGuided(c, e);
            return null;
        }
    }

    private void enterGuided(Conversation c, LlmGateway.Unavailable e) {
        if (c.guided) return;
        c.guided = true;
        Log.warn("ASSIST", "%s -> GUIDED MODE (no EU model deployment reachable)", c.id);
        audit.append("GUIDED_MODE_ON", c.id, c.sub, Json.obj("reason", "all model deployments unavailable"));
    }

    private void modelBack(Conversation c) {
        if (!c.guided) return;
        c.guided = false;
        Log.info("ASSIST", "%s -> AI mode restored (model served by %s)", c.id, llm.servedBy());
        audit.append("GUIDED_MODE_OFF", c.id, c.sub, Json.obj("servedBy", llm.servedBy()));
        c.add("assistant", "I'm fully available again, so you can type your questions as usual.", null);
    }

    private Map<String, Object> menuCard() {
        return Json.obj("type", "options", "action", "menu", "options", List.of(
                Json.obj("value", "start_dispute", "label", "Dispute a card payment"),
                Json.obj("value", "status", "label", "Check my disputes"),
                Json.obj("value", "agent", "label", "Talk to a colleague")));
    }

    private Map<String, Object> txnCard(Conversation c) {
        List<Object> opts = new ArrayList<>();
        for (Map<String, Object> tx : c.txnOptions) {
            Map<String, Object> a = Json.map(tx, "amount");
            Dispute open = openDisputeFor(c.sub, Json.str(tx, "txnRef"));
            boolean pending = !Boolean.TRUE.equals(tx.get("disputable"));
            opts.add(Json.obj("value", tx.get("txnRef"), "label", tx.get("merchant"),
                    "detail", tx.get("date") + " · " + Guardrails.money(Json.str(a, "value"), Json.str(a, "currency")),
                    "disabled", pending || open != null,
                    "note", open != null ? "already disputed: " + open.id + " (" + label(open.state) + ")" : pending ? "pending" : null));
        }
        return Json.obj("type", "options", "action", "select_txn", "options", opts);
    }

    private Map<String, Object> reasonCard() {
        List<Object> opts = new ArrayList<>();
        REASONS.forEach((k, v) -> opts.add(Json.obj("value", k, "label", v)));
        return Json.obj("type", "options", "action", "select_reason", "options", opts);
    }

    private Map<String, Object> possessionCard() {
        return Json.obj("type", "options", "action", "possession", "options", List.of(
                Json.obj("value", "yes", "label", "Yes, I have my card"), Json.obj("value", "no", "label", "No, it's lost or stolen")));
    }

    /** Deterministic confirmation card built from system data – not model text. */
    private Map<String, Object> confirmCard(Conversation c) {
        Map<String, Object> tx = c.selectedTxn;
        Map<String, Object> a = Json.map(tx, "amount");
        List<Object> rows = new ArrayList<>(List.of(
                List.of("Payment", tx.get("merchant")), List.of("Date", tx.get("date")),
                List.of("Amount", Guardrails.money(Json.str(a, "value"), Json.str(a, "currency"))),
                List.of("Card", c.cardDisplay), List.of("Reason", REASONS.get(c.reasonCode))));
        if (c.cardInPossession != null) rows.add(List.of("Card in your possession", c.cardInPossession ? "Yes" : "No"));
        return Json.obj("type", "confirm", "rows", rows);
    }

    private Map<String, Object> disputeCard(Dispute d) {
        return Json.obj("type", "dispute", "disputeId", d.id, "state", d.state, "stateLabel", label(d.state),
                "caseNumber", d.caseNumber);
    }

    static String label(Dispute.State s) {
        return switch (s) {
            case RECEIVED, CASE_OPEN, CORE_PENDING -> "Recorded, confirming with our systems";
            case CORE_REGISTERED -> "Registered";
            case IN_REVIEW -> "Being reviewed";
            case RESOLVED_ACCEPTED -> "Resolved: accepted";
            case RESOLVED_REJECTED -> "Resolved: not upheld";
            case MANUAL_REVIEW -> "Being registered by a colleague";
        };
    }

    private Map<String, Object> matchTxn(Conversation c, String t) {
        for (Map<String, Object> tx : c.txnOptions) {
            String merchant = Json.str(tx, "merchant").toLowerCase(Locale.ROOT);
            String firstWord = merchant.split("[ *]")[0];
            String amount = Json.str(Json.map(tx, "amount"), "value");
            if (t.contains(firstWord) || t.contains(amount)) return tx;
        }
        return null;
    }

    private void requireStep(Conversation c, Conversation.Step s) {
        if (c.step != s) throw new ApiException(Response.problem(409, "out-of-sequence", "That action isn't expected right now", "current step " + c.step));
    }

    private static String deterministicRequestId(Conversation c) {
        try {
            String seed = c.id + "|" + c.selectedTxn.get("txnRef") + "|" + c.reasonCode;
            return "req-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
