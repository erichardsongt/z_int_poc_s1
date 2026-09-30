package com.meridian.poc.llm;

import com.meridian.poc.common.Http;
import com.meridian.poc.common.Http.Response;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Log;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for the EU model endpoints behind the LLM gateway (design §6/§12): two deployments,
 * {@code eu-primary} and {@code eu-secondary}, each of which can be switched to DOWN (fast 503) or
 * SLOW (answers after the gateway's timeout) to exercise failover and guided mode.
 */
public final class MockModelEndpoint {
    public enum Mode { NORMAL, DOWN, SLOW }

    public static final String PRIMARY = "eu-primary", SECONDARY = "eu-secondary";
    private static final long SLOW_MS = 3000;

    private final LanguageModelStub model = new LanguageModelStub();
    private final Map<String, Mode> modes = new ConcurrentHashMap<>(Map.of(PRIMARY, Mode.NORMAL, SECONDARY, Mode.NORMAL));
    private final Http.Server server;

    public MockModelEndpoint(int port) throws IOException {
        server = new Http.Server("LLM", port)
                .post("/deployments/{d}/classify", r -> serve(r, () -> {
                    String text = Json.str(r.json(), "text");
                    return Json.obj("intent", model.classify(text).name(), "reason", model.extractReason(text));
                }))
                .post("/deployments/{d}/phrase", r -> serve(r, () -> Json.obj("text", model.phrase(Json.str(r.json(), "key")))))
                .get("/admin/state", r -> Response.json(200, state()))
                .post("/admin/chaos", r -> {
                    Map<String, Object> b = r.json();
                    if (b.get("primary") != null) modes.put(PRIMARY, Mode.valueOf(b.get("primary").toString()));
                    if (b.get("secondary") != null) modes.put(SECONDARY, Mode.valueOf(b.get("secondary").toString()));
                    Log.warn("CHAOS", "model endpoints -> %s=%s, %s=%s", PRIMARY, modes.get(PRIMARY), SECONDARY, modes.get(SECONDARY));
                    return Response.json(200, state());
                });
    }

    public void start() { server.start(); }
    public void stop() { server.stop(); }

    private interface Work { Map<String, Object> run(); }

    private Response serve(Http.Request r, Work w) throws InterruptedException {
        String d = r.param("d");
        Mode m = modes.get(d);
        if (m == null) return Response.problem(404, "unknown-deployment", "Unknown deployment", d);
        if (m == Mode.DOWN) return Response.problem(503, "model-unavailable", "Model deployment unavailable", d, "retryable", true);
        if (m == Mode.SLOW) Thread.sleep(SLOW_MS);
        Map<String, Object> out = w.run();
        out.put("deployment", d);
        return Response.json(200, out);
    }

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(PRIMARY, modes.get(PRIMARY));
        m.put(SECONDARY, modes.get(SECONDARY));
        return m;
    }
}
