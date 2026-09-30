package com.meridian.poc.assistant;

import com.meridian.poc.common.Http;
import com.meridian.poc.common.Json;

import java.util.Map;

/** A failed downstream call, classified for retry decisions (design §9). */
public final class RemoteError extends Exception {
    public final int status;          // 0 = timeout / transport failure
    public final boolean retryable;
    public final Map<String, Object> body;

    public RemoteError(String message, int status, boolean retryable, Map<String, Object> body) {
        super(message);
        this.status = status;
        this.retryable = retryable;
        this.body = body == null ? Map.of() : body;
    }

    /** Only 408/429/5xx and timeouts are retried – and only for idempotent or keyed operations. */
    public static RemoteError fromReply(String what, Http.Reply r) {
        Map<String, Object> body;
        try {
            Object parsed = Json.parse(r.body());
            body = parsed instanceof Map ? Json.parseObject(r.body()) : Json.obj("errors", parsed);
        } catch (RuntimeException e) {
            body = Json.obj("raw", r.body());
        }
        boolean retryable = r.status() == 408 || r.status() == 429 || r.status() >= 500;
        if (body.get("retryable") instanceof Boolean b) retryable = b;
        String detail = body.get("title") != null ? body.get("title").toString() : ("HTTP " + r.status());
        return new RemoteError(what + " failed: " + r.status() + " " + detail, r.status(), retryable, body);
    }

    public static RemoteError transport(String what, Exception e) {
        boolean timeout = e instanceof Http.Timeout;
        return new RemoteError(what + (timeout ? " timed out (outcome unknown)" : " unreachable: " + e.getMessage()), 0, true, null);
    }

    public boolean isTimeout() { return status == 0 || status == 504; }
}
