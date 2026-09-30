package com.meridian.poc.assistant;

import com.meridian.poc.Config;
import com.meridian.poc.common.Http;
import com.meridian.poc.common.Http.ApiException;
import com.meridian.poc.common.Http.Response;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Jwt;
import com.meridian.poc.common.Log;
import com.meridian.poc.salesforce.MockSalesforce;
import com.meridian.poc.security.TokenValidator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The assistant <b>data plane</b> (design §4): customer-facing chat API, dispute status API (pull-on-open /
 * polling), and the signed webhook that receives Salesforce case-status events. Also serves the demo console
 * and a few clearly-marked demo/admin endpoints that would not exist in production.
 */
public final class AssistantServer {
    private final Config cfg;
    private final Http.Server server;
    private final TokenValidator validator;
    private final ChatService chat;
    private final DisputeService disputes;
    private final AuditLog audit = new AuditLog();
    private final LlmGateway llm;

    public AssistantServer(Config cfg, KeyPair idpClientKey, KeyPair salesforceCertKey) throws IOException {
        this.cfg = cfg;
        this.validator = new TokenValidator(cfg.idpIssuer(), Config.AUD_ASSISTANT, cfg.idpJwks(), "meridian-assistant");
        TokenBroker tokens = new TokenBroker(cfg, idpClientKey.getPrivate(), "dataplane-2026-09");
        FacadeClient facade = new FacadeClient(cfg);
        SalesforceClient sf = new SalesforceClient(cfg, salesforceCertKey.getPrivate());
        this.llm = new LlmGateway(cfg);
        this.chat = new ChatService(cfg, facade, tokens, audit, llm);
        this.disputes = new DisputeService(cfg, facade, sf, tokens, audit, chat);
        chat.setDisputes(disputes);

        this.server = new Http.Server("ASSIST", cfg.assistantBindAddress, cfg.assistantPort)
                // ---- customer APIs (bearer AT1, audience meridian-assistant)
                .post("/v1/conversations", this::startConversation)
                .get("/v1/conversations/{id}", this::getConversation)
                .post("/v1/conversations/{id}/messages", this::postMessage)
                .get("/v1/disputes", this::listDisputes)
                .get("/v1/disputes/{id}", this::getDispute)
                // ---- Salesforce -> data plane (HMAC-signed)
                .post("/v1/webhooks/salesforce/case-status", this::caseStatusWebhook)
                // ---- demo-only: mobile-app simulator + ops console
                .get("/", r -> staticFile("/web/index.html", "text/html; charset=utf-8"))
                .get("/favicon.ico", r -> Response.empty(204))
                .get("/healthz", r -> Response.json(200, Json.obj("status", "ok")))
                .post("/demo/app-login", this::demoAppLogin)
                .get("/admin/state", r -> Response.json(200, adminState()))
                .post("/admin/chaos", this::chaos)
                .post("/admin/salesforce/cases/{ref}/status", this::agentStatus)
                .get("/admin/audit", r -> Response.bytes(200, audit.allText().getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8"));
    }

    public void start() { server.start(); disputes.start(); }
    public void stop() { disputes.stop(); server.stop(); }
    public AuditLog audit() { return audit; }
    public DisputeService disputes() { return disputes; }

    // ------------------------------------------------------------------ customer APIs
    private Map<String, Object> auth(Http.Request req) {
        Map<String, Object> claims = validator.validate(req.bearer());
        validator.requireScope(claims, "assistant.transactions.read");
        return claims;
    }

    private Response startConversation(Http.Request req) {
        Map<String, Object> claims = auth(req);
        Conversation c = chat.start(Json.str(claims, "sub"));
        return Response.json(201, Json.obj("conversationId", c.id, "assistantMode", mode(c), "messages", c.messagesAfter(0)));
    }

    private Response getConversation(Http.Request req) {
        Map<String, Object> claims = auth(req);
        Conversation c = chat.get(req.param("id"), Json.str(claims, "sub"));
        int after = Integer.parseInt(req.query.getOrDefault("after", "0"));
        return Response.json(200, Json.obj("conversationId", c.id, "step", c.step, "assistantMode", mode(c), "messages", c.messagesAfter(after)));
    }

    private Response postMessage(Http.Request req) {
        Map<String, Object> claims = auth(req);
        Conversation c = chat.get(req.param("id"), Json.str(claims, "sub"));
        List<Map<String, Object>> out = chat.handle(c, req.json(), req.bearer(), claims);
        return Response.json(200, Json.obj("conversationId", c.id, "step", c.step, "assistantMode", mode(c), "messages", out));
    }

    private static String mode(Conversation c) { return c.guided ? "guided" : "ai";
    }

    private Response listDisputes(Http.Request req) {
        Map<String, Object> claims = auth(req);
        return Response.json(200, Json.obj("disputes",
                disputes.forSubject(Json.str(claims, "sub")).stream().map(Dispute::view).toList()));
    }

    /** Pull-on-open / polling endpoint: the chat always shows current truth even if an event was missed. */
    private Response getDispute(Http.Request req) {
        Map<String, Object> claims = auth(req);
        Dispute d = disputes.get(req.param("id"));
        if (d == null || !d.sub.equals(Json.str(claims, "sub")))
            return Response.problem(404, "not-found", "Dispute not found", null);
        return Response.json(200, d.view());
    }

    // ------------------------------------------------------------------ webhook
    private Response caseStatusWebhook(Http.Request req) {
        String sig = req.header("X-Meridian-Signature");
        if (!validSignature(sig, req.body)) {
            Log.warn("WEBHOOK", "rejected event: invalid or stale signature");
            audit.append("WEBHOOK_REJECTED", null, null, Json.obj("reason", "bad signature"));
            return Response.problem(401, "bad-signature", "Invalid webhook signature", null);
        }
        Map<String, Object> ev = req.json();
        Log.info("WEBHOOK", "Dispute_Status__e %s: %s -> '%s' (v%s)", ev.get("eventUuid"), ev.get("disputeRef"), ev.get("status"), ev.get("caseVersion"));
        String result = disputes.onCaseStatusEvent(ev);
        return Response.json(200, Json.obj("result", result));
    }

    /** Signature = HMAC-SHA256(secret, timestamp + "." + body); 5-minute tolerance defeats replay; constant-time compare. */
    private boolean validSignature(String header, String body) {
        if (header == null) return false;
        Map<String, String> parts = new LinkedHashMap<>();
        for (String p : header.split(",")) {
            int eq = p.indexOf('=');
            if (eq > 0) parts.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        try {
            long ts = Long.parseLong(parts.getOrDefault("t", "0"));
            if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) return false;
            String expected = MockSalesforce.hmac(cfg.webhookSecret, ts + "." + body);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts.getOrDefault("v1", "").getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ demo-only helpers
    /**
     * Mobile-app simulator: performs what the real app does after its own login – RFC 8693 exchange of the
     * app token for an assistant-audience token (AT1). Returns decoded claims so the demo can show them.
     */
    private Response demoAppLogin(Http.Request req) throws IOException {
        Map<String, Object> b = req.json();
        Http.Reply login = Http.sendJson("POST", cfg.idpUrl() + "/demo/app-login", null,
                Json.obj("customerId", b.getOrDefault("customerId", "C-100234"), "acr", b.getOrDefault("acr", "low")), Duration.ofSeconds(2));
        if (!login.ok()) return Response.json(login.status(), login.json());
        String appToken = Json.str(login.json(), "access_token");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        form.put("client_id", Config.CLIENT_MOBILE);
        form.put("subject_token", appToken);
        form.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        form.put("audience", Config.AUD_ASSISTANT);
        form.put("scope", "assistant.accounts.read assistant.transactions.read assistant.disputes.write assistant.handoff");
        Http.Reply ex = Http.postForm(cfg.idpTokenEndpoint(), form, Duration.ofSeconds(2));
        if (!ex.ok()) return Response.json(ex.status(), ex.json());
        String at1 = Json.str(ex.json(), "access_token");
        return Response.json(200, Json.obj("accessToken", at1, "claims", Jwt.peek(at1), "appTokenClaims", Jwt.peek(appToken)));
    }

    private Response chaos(Http.Request req) throws IOException {
        Map<String, Object> b = req.json();
        String target = Json.str(b, "target");
        String url = "core".equals(target) ? cfg.coreUrl() : "salesforce".equals(target) ? cfg.salesforceUrl()
                : "model".equals(target) ? cfg.modelUrl() : null;
        if (url == null) return Response.problem(400, "bad-target", "target must be core, salesforce or model", null);
        b.remove("target");
        Http.Reply r = Http.sendJson("POST", url + "/admin/chaos", null, b, Duration.ofSeconds(2));
        return Response.json(r.status(), r.json());
    }

    private Response agentStatus(Http.Request req) throws IOException {
        Http.Reply r = Http.call("POST", cfg.salesforceUrl() + "/admin/cases/" + req.param("ref") + "/status", null,
                req.body, "application/json", Duration.ofSeconds(2));
        return Response.json(r.status(), r.json());
    }

    private Map<String, Object> adminState() {
        return Json.obj("disputes", disputes.allViews(), "outbox", disputes.outboxIds(),
                "coreCircuitBreaker", disputes.breakerState(),
                "audit", Json.obj("entries", audit.size(), "chainValid", audit.verify(), "tail", audit.tail(12)),
                "core", fetch(cfg.coreUrl() + "/admin/state"),
                "salesforce", fetch(cfg.salesforceUrl() + "/admin/state"),
                "model", Json.obj("endpoints", fetch(cfg.modelUrl() + "/admin/state"), "gateway", llm.state()));
    }

    private Object fetch(String url) {
        try {
            return Http.getJson(url, null, Duration.ofMillis(800)).json();
        } catch (Exception e) {
            return Json.obj("error", "unreachable");
        }
    }

    private Response staticFile(String path, String type) throws IOException {
        try (InputStream in = AssistantServer.class.getResourceAsStream(path)) {
            if (in == null) throw new ApiException(Response.problem(404, "not-found", "Not found", path));
            return Response.bytes(200, in.readAllBytes(), type);
        }
    }
}
