package com.meridian.poc.assistant;

import com.meridian.poc.Config;
import com.meridian.poc.common.Http;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Log;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM gateway (design §4/§12): routes every model call to the EU primary deployment, fails over to the EU
 * secondary, and – if both are unavailable – reports {@link Unavailable} so the orchestrator switches the
 * conversation to <b>guided mode</b> (buttons, bank-approved copy, no free-text understanding).
 * Each deployment has its own circuit breaker, so an outage costs one timeout, not one per turn.
 * There is deliberately no fallback outside the EU: residency takes precedence over availability.
 */
public final class LlmGateway {
    public static final class Unavailable extends Exception {
        Unavailable(String msg) { super(msg); }
    }

    /** What the model understood from a customer message. */
    public record Understanding(String intent, String reason) {}

    private static final List<String> DEPLOYMENTS = List.of("eu-primary", "eu-secondary");

    private final Config cfg;
    private final Map<String, CircuitBreaker> breakers = new LinkedHashMap<>();
    private volatile String servedBy = "eu-primary";

    public LlmGateway(Config cfg) {
        this.cfg = cfg;
        for (String d : DEPLOYMENTS)
            breakers.put(d, new CircuitBreaker("LLM", "model " + d, 4, 2, 0.5, cfg.llmBreakerOpenFor));
    }

    public Understanding understand(String redactedText) throws Unavailable {
        Map<String, Object> r = call("classify", Json.obj("text", redactedText));
        return new Understanding(Json.str(r, "intent"), Json.str(r, "reason"));
    }

    public String phrase(String key) throws Unavailable {
        return Json.str(call("phrase", Json.obj("key", key)), "text");
    }

    private Map<String, Object> call(String op, Map<String, Object> body) throws Unavailable {
        StringBuilder why = new StringBuilder();
        for (String d : DEPLOYMENTS) {
            try {
                Map<String, Object> r = breakers.get(d).call(() -> post(d, op, body));
                if (!d.equals(servedBy)) {
                    Log.warn("LLM", "model traffic now served by %s", d);
                    servedBy = d;
                }
                return r;
            } catch (RemoteError e) {
                why.append(d).append(": ").append(e.getMessage()).append("; ");
                if (d.equals(DEPLOYMENTS.get(0)) && !e.getMessage().contains("circuit OPEN")) Log.warn("LLM", "%s failed (%s) -> failing over to %s", d, e.getMessage(), DEPLOYMENTS.get(1));
            }
        }
        if (!"none".equals(servedBy)) {
            Log.warn("LLM", "ALL EU model deployments unavailable -> conversations switch to guided mode");
            servedBy = "none";
        }
        throw new Unavailable(why.toString());
    }

    private Map<String, Object> post(String deployment, String op, Map<String, Object> body) throws RemoteError {
        String what = "model " + deployment + " " + op;
        try {
            Http.Reply r = Http.sendJson("POST", cfg.modelUrl() + "/deployments/" + deployment + "/" + op, null, body, cfg.llmTimeout);
            if (!r.ok()) throw RemoteError.fromReply(what, r);
            return r.json();
        } catch (IOException e) {
            throw RemoteError.transport(what, e);
        }
    }

    /** eu-primary, eu-secondary, or "none" (guided mode). */
    public String servedBy() { return servedBy; }

    public Map<String, Object> state() {
        Map<String, Object> b = new LinkedHashMap<>();
        breakers.forEach((k, v) -> b.put(k, v.state().name()));
        return Json.obj("servedBy", servedBy, "breakers", b);
    }
}
