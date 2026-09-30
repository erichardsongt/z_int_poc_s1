package com.meridian.poc;

import com.meridian.poc.common.Http;
import com.meridian.poc.common.Json;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scripted end-to-end walkthrough ({@code ./run.sh demo}). Narration is printed between the services' own
 * trace logs, so the audience sees both the customer's view and what happened underneath.
 */
final class Demo {
    private static final boolean COLOR = System.getenv("NO_COLOR") == null && System.console() != null;

    static int run(int base) throws Exception {
        Config cfg = Config.forBasePort(base);
        try (Platform p = new Platform(cfg).start()) {
            DemoClient app = new DemoClient(cfg);

            scene(1, "IDENTITY: app session becomes a scoped, pseudonymous assistant token");
            Map<String, Object> login = app.login("C-100234", "low");
            Map<String, Object> appClaims = Json.map(login, "appTokenClaims");
            narrate("Mobile token:  sub=%s  aud=%s  acr=%s", appClaims.get("sub"), appClaims.get("aud"), appClaims.get("acr"));
            narrate("AT1 (RFC 8693): sub=%s  aud=%s  acr=%s", app.claims.get("sub"), app.claims.get("aud"), app.claims.get("acr"));
            narrate("                scope=%s", app.claims.get("scope"));
            narrate("→ The assistant never learns the customer number; it only sees a pairwise pseudonym.");
            pause();

            scene(2, "CAPTURE: conversational dispute intake (façade → SOAP core, allow-listed fields)");
            show(app.start());
            show(customer(app, "Hi, I don't recognise a charge on my card"));
            show(customerAction(app, "select_txn", "TXN-9A1F", "(taps) STREAMFLIX*EU PREMIUM"));
            show(customerAction(app, "select_reason", "UNAUTHORISED", "(taps) I didn't make this payment"));
            show(customerAction(app, "possession", "yes", "(taps) Yes, I have my card"));
            pause();

            scene(3, "STEP-UP: disputes need a recent strong authentication (RFC 9470)");
            String requestId = UUID.randomUUID().toString();
            narrate("App generates disputeRequestId=%s once, when the customer taps Confirm.", requestId);
            Http.Reply r = app.confirm(requestId);
            narrate("HTTP %d  WWW-Authenticate: %s", r.status(), r.header("WWW-Authenticate"));
            narrate("App runs biometric step-up and retries with the SAME disputeRequestId.");
            app.login("C-100234", "high");
            pause();

            scene(4, "CORE TIMES OUT *AFTER* COMMITTING: retry must not create a duplicate");
            app.chaosCore(Json.obj("mode", "NORMAL", "script", List.of("TIMEOUT_AFTER_COMMIT", "OK")));
            r = app.confirm(requestId);
            show(r);
            String dsp1 = DemoClient.disputeIdFrom(r);
            Map<String, Object> d1 = app.dispute(dsp1);
            narrate("%s → state=%s  case=%s  coreRef=%s", dsp1, d1.get("state"), d1.get("caseNumber"), d1.get("coreRef"));
            narrate("Core records for %s: %d  (core RegisterDispute calls: %s)", dsp1, p.core.recordsFor(dsp1),
                    p.core.state().get("registerCalls"));
            pause();

            scene(5, "DOUBLE-TAP: the same confirmation arrives twice");
            show(app.confirm(requestId));
            narrate("Salesforce cases: %d   core records for %s: %d  → nothing duplicated", p.salesforce.caseCount(), dsp1, p.core.recordsFor(dsp1));
            pause();

            scene(6, "CORE DOWN MID-DISPUTE: customer still gets a reference; the outbox finishes later");
            show(customer(app, "I think I was also charged twice for something"));
            show(customer(app, "The Greenleaf one"));
            show(customerAction(app, "select_reason", "DUPLICATE", "(taps) I was charged twice"));
            narrate("Core banking goes DOWN just before the customer confirms…");
            app.chaosCore(Json.obj("mode", "DOWN"));
            r = app.confirm(UUID.randomUUID().toString());
            show(r);
            String dsp2 = DemoClient.disputeIdFrom(r);
            narrate("%s is %s: case exists in Salesforce, core registration retrying in the background.", dsp2, app.dispute(dsp2).get("state"));
            Thread.sleep(3000);
            narrate("Core banking comes back…");
            app.chaosCore(Json.obj("mode", "NORMAL"));
            Map<String, Object> d2 = app.awaitState(dsp2, "CORE_REGISTERED", Duration.ofSeconds(30));
            narrate("%s → state=%s  coreRef=%s  attempts=%s", dsp2, d2.get("state"), d2.get("coreRef"), d2.get("attempts"));
            showAsync(app);
            pause();

            scene(7, "STATUS UPDATES: Salesforce event → signed webhook → chat + push (duplicate delivery ignored)");
            app.agentSetsStatus(dsp1, "In Review", true);
            Map<String, Object> d1b = app.awaitState(dsp1, "IN_REVIEW", Duration.ofSeconds(10));
            Thread.sleep(800);
            narrate("Pull-on-open: GET /v1/disputes/%s → %s", dsp1, d1b.get("state"));
            showAsync(app);
            narrate("The customer closes and reopens the app: a NEW conversation still shows their existing disputes.");
            show(app.start());
            show(customer(app, "I don't recognise a charge"));
            narrate("Payments that already have an open dispute are flagged and can't be disputed twice.");
            pause();

            scene(8, "AI MODEL OUTAGE: EU failover first, then guided (button-only) mode");
            show(customerAction(app, "cancel", null, "(taps) Cancel"));
            narrate("The primary EU model deployment goes down…");
            app.chaosModel("DOWN", "NORMAL");
            show(customer(app, "Any news on my disputes?"));
            narrate("Answered via eu-secondary: the customer doesn't notice anything.");
            narrate("Now BOTH EU deployments are down (by design there is no fallback outside the EU)…");
            app.chaosModel("DOWN", "DOWN");
            Http.Reply g = customer(app, "I'd like to dispute another payment please");
            show(g);
            narrate("assistantMode=%s: no free-text understanding, so the assistant offers buttons instead of guessing.",
                    g.json().get("assistantMode"));
            show(customerAction(app, "menu", "start_dispute", "(taps) Dispute a card payment"));
            show(customerAction(app, "select_txn", "TXN-77D0", "(taps) AIRLINK TRAVEL"));
            show(customerAction(app, "select_reason", "INCORRECT_AMOUNT", "(taps) The amount is wrong"));
            r = app.confirm(UUID.randomUUID().toString());
            show(r);
            String dsp3 = DemoClient.disputeIdFrom(r);
            narrate("%s → %s: captured and registered with no model at all, because the flows are deterministic.",
                    dsp3, app.dispute(dsp3).get("state"));
            narrate("The model comes back (the gateway re-probes after its circuit breaker cools down)…");
            app.chaosModel("NORMAL", "NORMAL");
            Thread.sleep(cfg.llmBreakerOpenFor.toMillis() + 500);
            show(customer(app, "Thanks. Can you show my disputes?"));
            pause();

            scene(9, "SECURITY NEGATIVES");
            Http.Reply forged = Http.call("POST", cfg.assistantUrl() + "/v1/webhooks/salesforce/case-status",
                    Map.of("X-Meridian-Signature", "t=" + (System.currentTimeMillis() / 1000) + ",v1=deadbeef"),
                    Json.write(Json.obj("eventUuid", "evil", "disputeRef", dsp1, "status", "Closed - Accepted", "caseVersion", 99)),
                    "application/json", Duration.ofSeconds(5));
            narrate("Forged webhook (bad HMAC)                  → HTTP %d", forged.status());
            String appToken = loginAppTokenOnly(cfg);
            Http.Reply wrongAud = Http.sendJson("POST", cfg.assistantUrl() + "/v1/conversations",
                    Map.of("Authorization", "Bearer " + appToken), Json.obj(), Duration.ofSeconds(5));
            narrate("Mobile-API token presented to assistant    → HTTP %d (%s)", wrongAud.status(), wrongAud.header("WWW-Authenticate"));
            DemoClient other = new DemoClient(cfg);
            other.login("C-100877", "high");
            narrate("Another customer reads %s             → HTTP %d", dsp1, other.disputeReply(dsp1).status());
            Http.Reply pan = app.say("My card number is 4111 1111 1111 1111, can you check it?");
            narrate("Card number typed into chat                → stored as: \"%s\"", firstCustomerText(pan));
            pause();

            scene(10, "SUMMARY");
            Map<String, Object> state = app.adminState();
            Map<String, Object> core = Json.map(state, "core");
            Map<String, Object> audit = Json.map(state, "audit");
            narrate("Disputes: %s = %s, %s = %s, %s = %s", dsp1, app.dispute(dsp1).get("state"), dsp2,
                    app.dispute(dsp2).get("state"), dsp3, app.dispute(dsp3).get("state"));
            narrate("Salesforce cases: %d   core records: %d   client refs with duplicates in core: %s",
                    p.salesforce.caseCount(), Json.list(core, "disputeRecords").size(), core.get("clientReferencesWithDuplicates"));
            narrate("Audit entries: %s   hash chain valid: %s", audit.get("entries"), audit.get("chainValid"));
            narrate("Explore interactively: ./run.sh  →  http://localhost:%d", cfg.assistantPort);
            System.out.println();
        }
        return 0;
    }

    private static String loginAppTokenOnly(Config cfg) throws Exception {
        Http.Reply r = Http.sendJson("POST", cfg.idpUrl() + "/demo/app-login", null,
                Json.obj("customerId", "C-100234", "acr", "high"), Duration.ofSeconds(5));
        return Json.str(r.json(), "access_token");
    }

    private static String firstCustomerText(Http.Reply r) {
        for (Object o : Json.list(r.json(), "messages")) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            if ("customer".equals(m.get("role"))) return m.get("text").toString();
        }
        return "?";
    }

    // ------------------------------------------------------------------ printing
    private static Http.Reply customer(DemoClient app, String text) throws Exception {
        line("36", "   customer  ▸ " + text);
        return app.say(text);
    }

    private static Http.Reply customerAction(DemoClient app, String action, String value, String shown) throws Exception {
        line("36", "   customer  ▸ " + shown);
        return app.act(action, value);
    }

    private static void show(Http.Reply r) {
        if (!r.ok()) {
            line("31", "   HTTP " + r.status() + " " + r.body());
            return;
        }
        for (Object o : Json.list(r.json(), "messages")) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            if ("customer".equals(m.get("role"))) continue;
            printAssistant(m);
        }
    }

    private static void showAsync(DemoClient app) throws Exception {
        for (Object o : app.newMessages()) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            if ("assistant".equals(m.get("role"))) printAssistant(m);
        }
    }

    @SuppressWarnings("unchecked")
    private static void printAssistant(Map<String, Object> m) {
        line("32", "   assistant ◂ " + m.get("text"));
        Map<String, Object> card = Json.map(m, "card");
        if (card == null) return;
        switch (String.valueOf(card.get("type"))) {
            case "options" -> {
                for (Object o : Json.list(card, "options")) {
                    Map<String, Object> opt = (Map<String, Object>) o;
                    line("90", "                 [" + opt.get("label") + (opt.get("detail") != null ? "  " + opt.get("detail") : "")
                            + (opt.get("note") != null ? "  (" + opt.get("note") + ")" : "") + "]");
                }
            }
            case "confirm" -> {
                for (Object o : Json.list(card, "rows")) {
                    List<Object> row = (List<Object>) o;
                    line("90", String.format("                 %-24s %s", row.get(0), row.get(1)));
                }
                line("90", "                 [Confirm]  [Cancel]");
            }
            case "disputes" -> {
                for (Object o : Json.list(card, "items")) {
                    Map<String, Object> d = (Map<String, Object>) o;
                    line("90", "                 ⟦" + d.get("disputeId") + " · " + d.get("merchant") + " " + d.get("amount")
                            + " · " + d.get("stateLabel") + (d.get("caseNumber") != null ? " · case " + d.get("caseNumber") : "") + "⟧");
                }
            }
            case "dispute" -> line("90", "                 ⟦" + card.get("disputeId") + " · " + card.get("stateLabel")
                    + (card.get("caseNumber") != null ? " · case " + card.get("caseNumber") : "") + "⟧");
            default -> { }
        }
    }

    private static void scene(int n, String title) {
        System.out.println();
        line("1;37", "════════════════════════════════════════════════════════════════════════════════");
        line("1;37", String.format(" %d/10  %s", n, title));
        line("1;37", "════════════════════════════════════════════════════════════════════════════════");
    }

    private static void narrate(String fmt, Object... args) { line("1;33", "   » " + String.format(fmt, args)); }

    private static synchronized void line(String color, String s) {
        System.out.println(COLOR ? "\u001b[" + color + "m" + s + "\u001b[0m" : s);
    }

    private static void pause() throws InterruptedException { Thread.sleep(600); }
}
