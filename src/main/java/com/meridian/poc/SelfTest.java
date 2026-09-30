package com.meridian.poc;

import com.meridian.poc.assistant.DisputeService;
import com.meridian.poc.assistant.Guardrails;
import com.meridian.poc.common.Http;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Jwt;
import com.meridian.poc.common.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dependency-free self-checks ({@code ./run.sh test}). Each check exercises the running services over HTTP
 * (or a unit directly) and asserts the property the design promises. Exit code 0 = all passed.
 */
final class SelfTest {
    private record Result(String name, boolean ok, String detail) {}

    private static final List<Result> results = new ArrayList<>();

    static int run(int base) throws Exception {
        Log.setQuiet(!"1".equals(System.getenv("VERBOSE")));
        System.out.println("\nMeridian dispute-intake prototype: self-checks\n");

        unitChecks();

        Config cfg = Config.forBasePort(base);
        try (Platform p = new Platform(cfg).start()) {
            securityChecks(cfg);
            sagaChecks(cfg, p);
            llmChecks(cfg, p);
        }
        Config fast = Config.forBasePort(base + 10, Duration.ofSeconds(3));
        try (Platform p2 = new Platform(fast).start()) {
            manualReviewCheck(fast, p2);
        }

        long failed = results.stream().filter(r -> !r.ok).count();
        System.out.printf("%n%d checks, %d passed, %d failed%n%n", results.size(), results.size() - failed, failed);
        return failed == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------ unit-level
    private static void unitChecks() {
        check("JSON round-trip preserves structure", () -> {
            String s = Json.write(Json.obj("a", List.of(1L, "x\"y", true), "b", Json.obj("c", null)));
            return s.equals(Json.write(Json.parse(s)));
        });
        KeyPair kp = Jwt.newRsaKeyPair();
        String jwt = Jwt.sign(Json.obj("sub", "x", "scope", "read"), kp.getPrivate(), "k1");
        check("JWT: valid RS256 signature verifies", () -> "x".equals(Jwt.verify(jwt, k -> kp.getPublic()).get("sub")));
        check("JWT: tampered payload is rejected", () -> {
            String[] parts = jwt.split("\\.");
            String forged = parts[0] + "." + Jwt.b64(Json.write(Json.obj("sub", "admin", "scope", "read")).getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
            return throwsJwt(() -> Jwt.verify(forged, k -> kp.getPublic()));
        });
        check("JWT: alg=none is rejected", () -> {
            String none = Jwt.b64("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + jwt.split("\\.")[1] + ".";
            return throwsJwt(() -> Jwt.verify(none + "x", k -> kp.getPublic()));
        });
        check("Guardrail: Luhn-valid card number is redacted", () ->
                Guardrails.redactPan("card 4111 1111 1111 1111 please").text().equals("card [card number removed] please"));
        check("Guardrail: non-Luhn digit string is left alone", () ->
                !Guardrails.redactPan("ref 4111111111111112").panFound());
        check("Guardrail: literal amount in model output is blocked", () -> {
            try { Guardrails.bind("You were charged €89.99", Map.of()); return false; }
            catch (IllegalStateException e) { return true; }
        });
        check("Guardrail: amounts only enter text via bound slots", () ->
                Guardrails.bind("You paid {{AMT}}", Map.of("AMT", "€89.99")).equals("You paid €89.99"));
    }

    // ------------------------------------------------------------------ identity & authorization
    private static void securityChecks(Config cfg) throws Exception {
        String appToken = Json.str(Http.sendJson("POST", cfg.idpUrl() + "/demo/app-login", null,
                Json.obj("customerId", "C-100234", "acr", "high"), Duration.ofSeconds(5)).json(), "access_token");
        Http.Reply r = Http.sendJson("POST", cfg.assistantUrl() + "/v1/conversations",
                Map.of("Authorization", "Bearer " + appToken), Json.obj(), Duration.ofSeconds(5));
        check("Token for another audience (mobile API) is rejected by the assistant", () ->
                r.status() == 401 && r.header("WWW-Authenticate").contains("wrong audience"));

        Http.Reply noToken = Http.sendJson("POST", cfg.assistantUrl() + "/v1/conversations", null, Json.obj(), Duration.ofSeconds(5));
        check("Missing bearer token → 401", () -> noToken.status() == 401);

        DemoClient anna = new DemoClient(cfg);
        anna.login("C-100234", "low");
        check("AT1 carries a pairwise subject, not the customer number", () ->
                anna.claims.get("sub").toString().startsWith("pw_") && !anna.claims.toString().contains("C-100234"));
        check("AT1 audience is the assistant only", () -> Config.AUD_ASSISTANT.equals(anna.claims.get("aud")));

        anna.start();
        capture(anna, "TXN-9A1F", "UNAUTHORISED");
        Http.Reply stepUp = anna.confirm(UUID.randomUUID().toString());
        check("Dispute with acr=low → 401 insufficient_user_authentication (step-up)", () ->
                stepUp.status() == 401 && stepUp.header("WWW-Authenticate").contains("insufficient_user_authentication"));

        DemoClient luca = new DemoClient(cfg);
        luca.login("C-100877", "high");
        Http.Reply peek = Http.getJson(cfg.assistantUrl() + "/v1/conversations/" + anna.conversationId, luca.auth(), Duration.ofSeconds(5));
        check("Another customer cannot read the conversation (404, no existence leak)", () -> peek.status() == 404);

        Http.Reply pan = anna.say("my card is 4111-1111-1111-1111");
        check("Card number typed in chat never reaches the transcript", () -> !pan.body().contains("4111"));
    }

    // ------------------------------------------------------------------ dispute saga
    private static void sagaChecks(Config cfg, Platform p) throws Exception {
        DemoClient app = new DemoClient(cfg);
        app.login("C-100234", "high");
        app.start();

        // 1) core commits, then times out: façade read-before-write must prevent a second record
        p.core.applyChaos(Json.obj("mode", "NORMAL", "script", List.of("TIMEOUT_AFTER_COMMIT", "OK")));
        capture(app, "TXN-9A1F", "UNAUTHORISED");
        String req1 = UUID.randomUUID().toString();
        Http.Reply r1 = app.confirm(req1);
        String dsp1 = DemoClient.disputeIdFrom(r1);
        Map<String, Object> d1 = app.dispute(dsp1);
        check("Timeout after commit → registered once (read-before-write)", () ->
                "CORE_REGISTERED".equals(d1.get("state")) && p.core.recordsFor(dsp1) == 1);

        // 2) double-tap / network retry: same key, same payload
        int casesBefore = p.salesforce.caseCount();
        Http.Reply again = app.confirm(req1);
        check("Same disputeRequestId again → same dispute, no new case", () ->
                dsp1.equals(DemoClient.disputeIdFrom(again)) && p.salesforce.caseCount() == casesBefore);

        // 3) same key, different payload
        capture(app, "TXN-77D0", "INCORRECT_AMOUNT");
        Http.Reply reuse = app.confirm(req1);
        check("Same disputeRequestId, different dispute → 422", () -> reuse.status() == 422);
        app.act("cancel", null);

        // 4) one open dispute per transaction, even with a fresh key (checked below the chat layer, which
        //    already blocks re-selecting a disputed payment - see check 8)
        DisputeService.SubmitResult dup = p.assistant.disputes().submit(app.claims.get("sub").toString(), app.conversationId,
                UUID.randomUUID().toString(),
                new DisputeService.Draft("CARD-7F3A", "Visa •• 4821", "TXN-9A1F", "STREAMFLIX*EU PREMIUM", "89.99", "EUR",
                        "2026-09-24", "DUPLICATE", "I was charged twice", null),
                new DisputeService.CustomerAuth(app.token, app.claims));
        check("Second dispute on the same transaction → existing dispute returned", () ->
                dup.existingDispute() && dsp1.equals(dup.dispute().id) && p.salesforce.caseCount() == casesBefore);

        // 5) core down mid-dispute → CORE_PENDING, then the outbox completes it with a service token
        capture(app, "TXN-5C22", "DUPLICATE");
        p.core.applyChaos(Json.obj("mode", "DOWN", "script", List.of()));
        Http.Reply r5 = app.confirm(UUID.randomUUID().toString());
        String dsp5 = DemoClient.disputeIdFrom(r5);
        Map<String, Object> pending = app.dispute(dsp5);
        check("Core down → customer still gets a reference and an open case", () ->
                dsp5 != null && "CORE_PENDING".equals(pending.get("state")) && pending.get("caseNumber") != null);
        Thread.sleep(1500);
        p.core.applyChaos(Json.obj("mode", "NORMAL"));
        Map<String, Object> done5 = app.awaitState(dsp5, "CORE_REGISTERED", Duration.ofSeconds(30));
        check("Core recovers → outbox registers it exactly once", () ->
                "CORE_REGISTERED".equals(done5.get("state")) && p.core.recordsFor(dsp5) == 1);
        List<Object> async = app.newMessages();
        check("Customer is told asynchronously (chat update)", () -> Json.write(async).contains("now registered"));

        // 6) Salesforce down → record + reference; case created when it returns
        capture(app, "TXN-77D0", "INCORRECT_AMOUNT");
        p.core.applyChaos(Json.obj("mode", "NORMAL"));
        app.chaosSalesforce("DOWN");
        Http.Reply r6 = app.confirm(UUID.randomUUID().toString());
        String dsp6 = DemoClient.disputeIdFrom(r6);
        Map<String, Object> d6 = app.dispute(dsp6);
        check("Salesforce down → dispute RECEIVED with reference, nothing lost", () ->
                "RECEIVED".equals(d6.get("state")) && d6.get("caseNumber") == null);
        Thread.sleep(1000);
        app.chaosSalesforce("NORMAL");
        Map<String, Object> d6b = app.awaitState(dsp6, "CORE_REGISTERED", Duration.ofSeconds(30));
        check("Salesforce recovers → case created, then core registered", () ->
                "CORE_REGISTERED".equals(d6b.get("state")) && d6b.get("caseNumber") != null);

        // 7) status events: duplicate delivery processed once; out-of-order ignored; forged rejected
        app.agentSetsStatus(dsp1, "In Review", true);
        Map<String, Object> rev = app.awaitState(dsp1, "IN_REVIEW", Duration.ofSeconds(10));
        Thread.sleep(700);
        long transitions = p.assistant.audit().allText().lines()
                .filter(l -> l.contains("DISPUTE_STATE") && l.contains(dsp1) && l.contains("\"to\":\"IN_REVIEW\"")).count();
        check("Status event delivered twice → applied once", () -> "IN_REVIEW".equals(rev.get("state")) && transitions == 1);
        String stale = p.assistant.disputes().onCaseStatusEvent(Json.obj("eventUuid", UUID.randomUUID().toString(),
                "disputeRef", dsp1, "status", "New", "caseVersion", 0L));
        check("Out-of-order (older version) event is ignored", () -> "stale".equals(stale));
        Http.Reply forged = Http.call("POST", cfg.assistantUrl() + "/v1/webhooks/salesforce/case-status",
                Map.of("X-Meridian-Signature", "t=" + (System.currentTimeMillis() / 1000) + ",v1=00"),
                Json.write(Json.obj("eventUuid", "x", "disputeRef", dsp1, "status", "Closed - Accepted", "caseVersion", 99)),
                "application/json", Duration.ofSeconds(5));
        check("Webhook with bad signature → 401 and no state change", () ->
                forged.status() == 401 && "IN_REVIEW".equals(dispute(app, dsp1).get("state")));

        // 8) existing disputes are visible to the customer (pull-on-open, status intent, flagged payments)
        Http.Reply reopened = app.start();
        check("New conversation shows the customer's existing disputes (pull-on-open)", () ->
                reopened.body().contains("\"type\":\"disputes\"") && reopened.body().contains(dsp1) && reopened.body().contains(dsp5));
        Http.Reply status = app.say("show my disputes");
        check("'show my disputes' lists disputes instead of starting a new one", () ->
                status.body().contains("\"type\":\"disputes\"") && !status.body().contains("select_txn"));
        Http.Reply list = app.act("start_dispute", null);
        check("Payments with an open dispute are flagged and not selectable", () ->
                list.body().contains("already disputed: " + dsp1));
        Http.Reply reselect = app.act("select_txn", "TXN-9A1F");
        check("Selecting an already-disputed payment shows the existing dispute", () ->
                reselect.ok() && reselect.body().contains("You already have an open dispute") && reselect.body().contains(dsp1));
        DemoClient otherCustomer = new DemoClient(cfg);
        otherCustomer.login("C-100877", "high");
        Http.Reply otherStart = otherCustomer.start();
        check("Another customer's new conversation shows none of these disputes", () ->
                !otherStart.body().contains(dsp1) && !otherStart.body().contains("\"type\":\"disputes\""));

        // 9) audit
        String auditText = p.assistant.audit().allText();
        check("Audit hash chain verifies", () -> p.assistant.audit().verify());
        check("Audit log contains no card numbers or customer numbers", () ->
                !auditText.matches("(?s).*4111[ -]?1111[ -]?1111[ -]?1111.*") && !auditText.contains("C-100234"));
        check("No duplicate core records for any dispute", () ->
                ((Number) p.core.state().get("clientReferencesWithDuplicates")).longValue() == 0);
    }

    // ------------------------------------------------------------------ LLM outage: failover, then guided mode
    private static void llmChecks(Config cfg, Platform p) throws Exception {
        p.core.applyChaos(Json.obj("mode", "NORMAL", "script", List.of()));
        DemoClient app = new DemoClient(cfg);
        app.login("C-100234", "high");
        app.start();

        app.chaosModel("DOWN", "NORMAL");
        Http.Reply viaSecondary = app.say("show my disputes");
        check("Primary model down → gateway fails over to eu-secondary, still AI mode", () ->
                "ai".equals(viaSecondary.json().get("assistantMode")) && viaSecondary.body().contains("\"type\":\"disputes\"")
                        && Json.write(app.adminState()).contains("\"servedBy\":\"eu-secondary\""));

        app.chaosModel("DOWN", "DOWN");
        Http.Reply opened = app.start();
        check("All model deployments down → new conversation opens in guided mode with a menu", () ->
                "guided".equals(opened.json().get("assistantMode")) && opened.body().contains("guided mode")
                        && opened.body().contains("\"action\":\"menu\""));
        Http.Reply freeText = app.say("I don't recognise a charge");
        check("Guided mode: free text is answered with the button menu, not guessed at", () ->
                freeText.body().contains("can't read free text") && freeText.body().contains("\"action\":\"menu\"")
                        && !freeText.body().contains("select_txn"));
        Http.Reply list = app.act("menu", "start_dispute");
        check("Guided mode: dispute flow runs on buttons with bank-approved copy", () ->
                list.body().contains("Select the payment you want to dispute") && list.body().contains("select_txn"));
        app.act("select_txn", "TXN-4E90");
        app.act("select_reason", "NOT_RECEIVED");
        Http.Reply done = app.confirm(UUID.randomUUID().toString());
        String dsp = DemoClient.disputeIdFrom(done);
        check("Guided mode: dispute is still captured and registered end to end", () ->
                dsp != null && "CORE_REGISTERED".equals(app.dispute(dsp).get("state")));
        check("Guided mode is recorded in the audit trail", () ->
                p.assistant.audit().allText().contains("GUIDED_MODE_ON"));

        app.chaosModel("NORMAL", "NORMAL");
        Thread.sleep(cfg.llmBreakerOpenFor.toMillis() + 500);
        Http.Reply back = app.say("show my disputes");
        check("Model recovers → conversation returns to AI mode and says so", () ->
                "ai".equals(back.json().get("assistantMode")) && back.body().contains("fully available again"));

        app.chaosModel("SLOW", "NORMAL");
        long t0 = System.currentTimeMillis();
        Http.Reply slow = app.start();
        long took = System.currentTimeMillis() - t0;
        check("Slow primary → times out and fails over within ~1.5 s (no guided mode)", () ->
                "ai".equals(slow.json().get("assistantMode")) && took < 2500);
        app.chaosModel("NORMAL", "NORMAL");
    }

    private static void manualReviewCheck(Config cfg, Platform p) throws Exception {
        DemoClient app = new DemoClient(cfg);
        app.login("C-100234", "high");
        app.start();
        capture(app, "TXN-4E90", "NOT_RECEIVED");
        Http.Reply r = Http.sendJson("POST", cfg.assistantUrl() + "/admin/chaos", null,
                Json.obj("target", "core", "mode", "DOWN"), Duration.ofSeconds(5));
        String dsp = DemoClient.disputeIdFrom(app.confirm(UUID.randomUUID().toString()));
        Map<String, Object> d = app.awaitState(dsp, "MANUAL_REVIEW", Duration.ofSeconds(20));
        check("Retries exhausted → MANUAL_REVIEW (back-office queue) and customer told", () ->
                r.ok() && "MANUAL_REVIEW".equals(d.get("state")) && Json.write(app.newMessages()).contains("colleague"));
        check("Manual review leaves no core record behind (nothing half-written)", () -> p.core.recordsFor(dsp) == 0);
    }

    // ------------------------------------------------------------------ helpers
    private static void capture(DemoClient app, String txnRef, String reason) throws Exception {
        app.act("start_dispute", null);
        app.act("select_txn", txnRef);
        app.act("select_reason", reason);
        if ("UNAUTHORISED".equals(reason)) app.act("possession", "yes");
    }

    private static Map<String, Object> dispute(DemoClient app, String id) {
        try { return app.dispute(id); } catch (Exception e) { return Map.of(); }
    }

    @FunctionalInterface private interface Check { boolean run() throws Exception; }
    @FunctionalInterface private interface Thrower { void run() throws Exception; }

    private static boolean throwsJwt(Thrower t) {
        try { t.run(); return false; } catch (Jwt.JwtException e) { return true; } catch (Exception e) { return false; }
    }

    private static void check(String name, Check c) {
        boolean ok;
        String detail = "";
        try { ok = c.run(); } catch (Exception e) { ok = false; detail = e.toString(); }
        results.add(new Result(name, ok, detail));
        System.out.printf("  %s  %s%s%n", ok ? "PASS" : "FAIL", name, detail.isEmpty() ? "" : "  (" + detail + ")");
    }
}
