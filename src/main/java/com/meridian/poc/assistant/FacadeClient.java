package com.meridian.poc.assistant;

import com.meridian.poc.Config;
import com.meridian.poc.common.Http;
import com.meridian.poc.common.Json;

import java.io.IOException;
import java.util.Map;

/** Data plane → Meridian Assistant API façade (REST over private connectivity in production). */
public final class FacadeClient {
    private final Config cfg;

    public FacadeClient(Config cfg) { this.cfg = cfg; }

    public Map<String, Object> cards(String at2) throws RemoteError {
        return get("/v1/cards", at2, "façade GET /v1/cards");
    }

    public Map<String, Object> transactions(String at2, String cardRef) throws RemoteError {
        return get("/v1/cards/" + cardRef + "/transactions", at2, "façade GET transactions");
    }

    /** POST /v1/disputes with Idempotency-Key = our dispute id, so every retry is safe. */
    public Map<String, Object> registerDispute(String token, String idempotencyKey, Map<String, Object> body) throws RemoteError {
        try {
            Http.Reply r = Http.sendJson("POST", cfg.facadeUrl() + "/v1/disputes",
                    Map.of("Authorization", "Bearer " + token, "Idempotency-Key", idempotencyKey), body, cfg.facadeWriteTimeout);
            if (!r.ok()) throw RemoteError.fromReply("façade POST /v1/disputes", r);
            Map<String, Object> out = r.json();
            if ("true".equals(r.header("Idempotent-Replayed"))) out.put("replayed", true);
            return out;
        } catch (IOException e) {
            throw RemoteError.transport("façade POST /v1/disputes", e);
        }
    }

    private Map<String, Object> get(String path, String at2, String what) throws RemoteError {
        try {
            Http.Reply r = Http.getJson(cfg.facadeUrl() + path, Map.of("Authorization", "Bearer " + at2), cfg.facadeReadTimeout);
            if (!r.ok()) throw RemoteError.fromReply(what, r);
            return r.json();
        } catch (IOException e) {
            throw RemoteError.transport(what, e);
        }
    }

    static String str(Map<String, Object> m, String k) { return Json.str(m, k); }
}
