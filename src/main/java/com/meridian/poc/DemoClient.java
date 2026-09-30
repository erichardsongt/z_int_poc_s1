package com.meridian.poc;

import com.meridian.poc.common.Http;
import com.meridian.poc.common.Json;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plays the mobile app (and the ops team) against the running services. Used by Demo and SelfTest. */
final class DemoClient {
    private static final Duration T = Duration.ofSeconds(15);
    final Config cfg;
    String token;
    Map<String, Object> claims;
    String conversationId;
    int lastMessageId;

    DemoClient(Config cfg) { this.cfg = cfg; }

    Map<String, Object> login(String customerId, String acr) throws IOException {
        Http.Reply r = Http.sendJson("POST", cfg.assistantUrl() + "/demo/app-login", null,
                Json.obj("customerId", customerId, "acr", acr), T);
        Map<String, Object> m = r.json();
        token = Json.str(m, "accessToken");
        claims = Json.map(m, "claims");
        return m;
    }

    Http.Reply start() throws IOException {
        Http.Reply r = Http.sendJson("POST", cfg.assistantUrl() + "/v1/conversations", auth(), Json.obj(), T);
        if (r.ok()) {
            conversationId = Json.str(r.json(), "conversationId");
            track(r);
        }
        return r;
    }

    Http.Reply say(String text) throws IOException { return send(Json.obj("text", text)); }

    Http.Reply act(String action, String value) throws IOException { return send(Json.obj("action", action, "value", value)); }

    Http.Reply confirm(String requestId) throws IOException {
        return send(Json.obj("action", "confirm", "disputeRequestId", requestId));
    }

    Http.Reply send(Map<String, Object> body) throws IOException {
        Http.Reply r = Http.sendJson("POST", cfg.assistantUrl() + "/v1/conversations/" + conversationId + "/messages", auth(), body, T);
        if (r.ok()) track(r);
        return r;
    }

    /** Messages that arrived asynchronously (saga updates, status events) since we last looked. */
    List<Object> newMessages() throws IOException {
        Http.Reply r = Http.getJson(cfg.assistantUrl() + "/v1/conversations/" + conversationId + "?after=" + lastMessageId, auth(), T);
        List<Object> msgs = Json.list(r.json(), "messages");
        track(r);
        return msgs;
    }

    Map<String, Object> dispute(String id) throws IOException {
        return Http.getJson(cfg.assistantUrl() + "/v1/disputes/" + id, auth(), T).json();
    }

    Http.Reply disputeReply(String id) throws IOException {
        return Http.getJson(cfg.assistantUrl() + "/v1/disputes/" + id, auth(), T);
    }

    void chaosCore(Map<String, Object> body) throws IOException {
        Map<String, Object> b = new HashMap<>(body);
        b.put("target", "core");
        Http.sendJson("POST", cfg.assistantUrl() + "/admin/chaos", null, b, T);
    }

    void chaosModel(String primary, String secondary) throws IOException {
        Http.sendJson("POST", cfg.assistantUrl() + "/admin/chaos", null,
                Json.obj("target", "model", "primary", primary, "secondary", secondary), T);
    }

    void chaosSalesforce(String mode) throws IOException {
        Http.sendJson("POST", cfg.assistantUrl() + "/admin/chaos", null, Json.obj("target", "salesforce", "mode", mode), T);
    }

    Http.Reply agentSetsStatus(String disputeId, String status, boolean duplicate) throws IOException {
        return Http.sendJson("POST", cfg.assistantUrl() + "/admin/salesforce/cases/" + disputeId + "/status", null,
                Json.obj("status", status, "duplicateDelivery", duplicate), T);
    }

    Map<String, Object> adminState() throws IOException {
        return Http.getJson(cfg.assistantUrl() + "/admin/state", null, T).json();
    }

    /** Poll until the dispute reaches the wanted state (or time out). */
    Map<String, Object> awaitState(String disputeId, String state, Duration max) throws IOException, InterruptedException {
        long end = System.currentTimeMillis() + max.toMillis();
        Map<String, Object> d = dispute(disputeId);
        while (!state.equals(d.get("state")) && System.currentTimeMillis() < end) {
            Thread.sleep(250);
            d = dispute(disputeId);
        }
        return d;
    }

    static String disputeIdFrom(Http.Reply r) {
        for (Object o : Json.list(r.json(), "messages")) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Object> card = Json.map(m, "card");
            if (card != null && card.get("disputeId") != null) return card.get("disputeId").toString();
        }
        return null;
    }

    private void track(Http.Reply r) {
        for (Object o : Json.list(r.json(), "messages")) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            lastMessageId = Math.max(lastMessageId, ((Number) m.get("id")).intValue());
        }
    }

    Map<String, String> auth() { return Map.of("Authorization", "Bearer " + token); }
}
