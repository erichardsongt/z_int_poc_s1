package com.meridian.poc.salesforce;

import com.meridian.poc.Config;
import com.meridian.poc.common.Http;
import com.meridian.poc.common.Http.Response;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Jwt;
import com.meridian.poc.common.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for Salesforce Financial Services Cloud:
 * <ul>
 *   <li>OAuth 2.0 JWT bearer flow for the integration user (connected app with a registered certificate);</li>
 *   <li>REST <b>upsert by external ID</b> on Case ({@code PATCH /sobjects/Case/Dispute_Ref__c/{ref}}):
 *       201 on create, 200 on update — the same ref can never create two cases;</li>
 *   <li>Case status changes published as events. In production the assistant subscribes via the Pub/Sub API
 *       (gRPC, replay IDs); here they are pushed as an HMAC-signed webhook, delivered at-least-once
 *       (with optional deliberate duplicates) so the consumer's de-duplication can be demonstrated.</li>
 * </ul>
 */
public final class MockSalesforce {
    public enum Mode { NORMAL, DOWN }

    private static final String API = "/services/data/v61.0/sobjects/Case/Dispute_Ref__c/";

    private static final class CaseRec {
        String id, caseNumber, disputeRef, status;
        long version;
        final Map<String, Object> fields = new LinkedHashMap<>();
        String createdAt, lastModifiedAt;
    }

    private final Config cfg;
    private final Http.Server server;
    private final Map<String, CaseRec> cases = new ConcurrentHashMap<>();
    private final Map<String, Instant> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, PublicKey> connectedAppCerts = new ConcurrentHashMap<>();
    private final AtomicInteger caseSeq = new AtomicInteger(12340);
    private final AtomicLong replayId = new AtomicLong(9_000);
    private final AtomicInteger upsertCalls = new AtomicInteger();
    private final ExecutorService delivery = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sfdc-event-delivery");
        t.setDaemon(true);
        return t;
    });
    private final List<Object> deliveries = new ArrayList<>();
    private volatile Mode mode = Mode.NORMAL;

    public MockSalesforce(Config cfg) throws IOException {
        this.cfg = cfg;
        this.server = new Http.Server("SFDC", cfg.salesforcePort)
                .post("/services/oauth2/token", this::jwtBearer)
                .patch(API + "{ref}", this::upsert)
                .get(API + "{ref}", this::getCase)
                .get("/admin/state", r -> Response.json(200, state()))
                .post("/admin/chaos", r -> {
                    mode = Mode.valueOf(Json.str(r.json(), "mode"));
                    Log.warn("CHAOS", "salesforce mode -> %s", mode);
                    return Response.json(200, state());
                })
                .post("/admin/cases/{ref}/status", this::agentChangesStatus);
    }

    public void registerConnectedApp(String consumerKey, PublicKey cert) { connectedAppCerts.put(consumerKey, cert); }

    public void start() { server.start(); }
    public void stop() { server.stop(); delivery.shutdownNow(); }

    // ------------------------------------------------------------------ OAuth JWT bearer flow
    private Response jwtBearer(Http.Request req) {
        Map<String, String> f = req.form();
        if (!"urn:ietf:params:oauth:grant-type:jwt-bearer".equals(f.get("grant_type")))
            return Response.json(400, Json.obj("error", "unsupported_grant_type"));
        try {
            String assertion = f.get("assertion");
            String iss = Json.str(Jwt.peek(assertion), "iss");
            PublicKey cert = connectedAppCerts.get(iss);
            if (cert == null) return Response.json(400, Json.obj("error", "invalid_client_id"));
            Map<String, Object> c = Jwt.verify(assertion, k -> cert);
            if (!Config.SF_INTEGRATION_USER.equals(c.get("sub")) || !cfg.salesforceUrl().equals(c.get("aud"))
                    || ((Number) c.get("exp")).longValue() < Instant.now().getEpochSecond())
                return Response.json(400, Json.obj("error", "invalid_grant", "error_description", "user hasn't approved this consumer"));
        } catch (Exception e) {
            return Response.json(400, Json.obj("error", "invalid_grant", "error_description", e.getMessage()));
        }
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String token = "00DMERIDIAN!" + Jwt.b64(b);
        accessTokens.put(token, Instant.now().plus(Duration.ofHours(1)));
        Log.info("SFDC", "JWT bearer flow OK for %s -> access token issued", Config.SF_INTEGRATION_USER);
        return Response.json(200, Json.obj("access_token", token, "instance_url", cfg.salesforceUrl(),
                "token_type", "Bearer", "scope", "api"));
    }

    private Response requireAuth(Http.Request req) {
        String t = req.bearer();
        Instant exp = t == null ? null : accessTokens.get(t);
        if (exp == null || exp.isBefore(Instant.now()))
            return Response.json(401, List.of(Json.obj("errorCode", "INVALID_SESSION_ID", "message", "Session expired or invalid")));
        if (mode == Mode.DOWN)
            return Response.json(503, List.of(Json.obj("errorCode", "SERVER_UNAVAILABLE", "message", "Service temporarily unavailable")))
                    .header("Retry-After", "2");
        return null;
    }

    // ------------------------------------------------------------------ Case upsert by external id
    private Response upsert(Http.Request req) {
        upsertCalls.incrementAndGet();
        Response denied = requireAuth(req);
        if (denied != null) {
            if (denied.status == 503) Log.warn("SFDC", "upsert Case %s -> 503 SERVER_UNAVAILABLE  [chaos]", req.param("ref"));
            return denied;
        }
        String ref = req.param("ref");
        Map<String, Object> body = req.json();
        String now = Instant.now().toString();
        synchronized (cases) {
            CaseRec c = cases.get(ref);
            boolean created = c == null;
            if (created) {
                c = new CaseRec();
                c.id = "500" + UUID.randomUUID().toString().replace("-", "").substring(0, 15).toUpperCase();
                c.caseNumber = String.format("%08d", caseSeq.incrementAndGet());
                c.disputeRef = ref;
                c.status = "New";
                c.createdAt = now;
                cases.put(ref, c);
            }
            c.fields.putAll(body);
            if (body.get("Status") != null && !body.get("Status").equals(c.status)) {
                c.status = body.get("Status").toString();
                c.version++;
            }
            c.lastModifiedAt = now;
            Log.info("SFDC", "upsert Case Dispute_Ref__c=%s -> %s %s (CaseNumber %s)", ref,
                    created ? "201 CREATED" : "200 UPDATED", c.id, c.caseNumber);
            return Response.json(created ? 201 : 200, Json.obj("id", c.id, "success", true, "errors", List.of(), "created", created));
        }
    }

    private Response getCase(Http.Request req) {
        Response denied = requireAuth(req);
        if (denied != null) return denied;
        CaseRec c = cases.get(req.param("ref"));
        if (c == null) return Response.json(404, List.of(Json.obj("errorCode", "NOT_FOUND", "message", "not found")));
        return Response.json(200, view(c));
    }

    // ------------------------------------------------------------------ agents move the case; events flow out
    private Response agentChangesStatus(Http.Request req) {
        CaseRec c = cases.get(req.param("ref"));
        if (c == null) return Response.problem(404, "not-found", "No case for that dispute ref", null);
        String status = Json.str(req.json(), "status");
        boolean duplicate = Boolean.TRUE.equals(req.json().get("duplicateDelivery"));
        Map<String, Object> event;
        synchronized (cases) {
            c.status = status;
            c.version++;
            c.lastModifiedAt = Instant.now().toString();
            event = Json.obj("eventUuid", UUID.randomUUID().toString(), "replayId", replayId.incrementAndGet(),
                    "schema", "Dispute_Status__e", "caseId", c.id, "caseNumber", c.caseNumber,
                    "disputeRef", c.disputeRef, "status", c.status, "caseVersion", c.version,
                    "occurredAt", c.lastModifiedAt);
        }
        Log.info("SFDC", "agent set Case %s -> '%s' (v%d); publishing Dispute_Status__e%s", c.caseNumber, status, c.version,
                duplicate ? " [chaos: duplicate delivery]" : "");
        deliver(event);
        if (duplicate) deliver(event);
        return Response.json(200, view(c));
    }

    private void deliver(Map<String, Object> event) {
        delivery.submit(() -> {
            String body = Json.write(event);
            for (int attempt = 1; attempt <= 3; attempt++) {
                long ts = Instant.now().getEpochSecond();
                try {
                    Http.Reply r = Http.call("POST", cfg.assistantUrl() + "/v1/webhooks/salesforce/case-status",
                            Map.of("X-Meridian-Signature", "t=" + ts + ",v1=" + hmac(cfg.webhookSecret, ts + "." + body),
                                    "X-Event-Id", event.get("eventUuid").toString()),
                            body, "application/json", Duration.ofSeconds(2));
                    synchronized (deliveries) {
                        deliveries.add(Json.obj("eventUuid", event.get("eventUuid"), "attempt", attempt, "status", r.status()));
                    }
                    if (r.ok()) return;
                } catch (IOException e) {
                    // receiver down: back off and retry (at-least-once)
                }
                try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) { return; }
            }
            Log.warn("SFDC", "event %s undelivered after 3 attempts (subscriber would replay by replayId)", event.get("eventUuid"));
        });
    }

    public static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ admin
    private Map<String, Object> view(CaseRec c) {
        Map<String, Object> m = Json.obj("Id", c.id, "CaseNumber", c.caseNumber, "Dispute_Ref__c", c.disputeRef,
                "Status", c.status, "Version", c.version, "CreatedDate", c.createdAt, "LastModifiedDate", c.lastModifiedAt);
        c.fields.forEach(m::putIfAbsent);
        return m;
    }

    public Map<String, Object> state() {
        List<Object> list = new ArrayList<>();
        cases.values().forEach(c -> list.add(view(c)));
        List<Object> recent;
        synchronized (deliveries) {
            recent = new ArrayList<>(deliveries.subList(Math.max(0, deliveries.size() - 10), deliveries.size()));
        }
        return Json.obj("mode", mode, "upsertCalls", upsertCalls.get(), "cases", list, "recentEventDeliveries", recent);
    }

    public int caseCount() { return cases.size(); }
}
