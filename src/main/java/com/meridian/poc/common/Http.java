package com.meridian.poc.common;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny HTTP toolkit on top of the JDK's built-in server and client.
 * Every mock and the assistant itself run as real HTTP services on localhost,
 * so timeouts, status codes and headers behave like the real integration.
 */
public final class Http {
    private Http() {}

    // =================================================================== server
    @FunctionalInterface
    public interface Handler { Response handle(Request req) throws Exception; }

    public static final class Server {
        private final String tag;
        private final HttpServer server;
        private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        private final List<Route> routes = new ArrayList<>();

        private record Route(String method, Pattern pattern, List<String> names, Handler handler) {}

        public Server(String tag, int port) throws IOException { this(tag, "127.0.0.1", port); }

        /** Services bind to loopback by default; only the assistant may be bound wider (inside a container). */
        public Server(String tag, String bindAddress, int port) throws IOException {
            this.tag = tag;
            this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 64);
            this.server.setExecutor(pool);
            this.server.createContext("/", this::dispatch);
        }

        public Server get(String path, Handler h) { return route("GET", path, h); }
        public Server post(String path, Handler h) { return route("POST", path, h); }
        public Server patch(String path, Handler h) { return route("PATCH", path, h); }

        public Server route(String method, String path, Handler h) {
            List<String> names = new ArrayList<>();
            Matcher m = Pattern.compile("\\{(\\w+)}").matcher(path);
            StringBuilder regex = new StringBuilder();
            int last = 0;
            while (m.find()) {
                regex.append(Pattern.quote(path.substring(last, m.start()))).append("([^/]+)");
                names.add(m.group(1));
                last = m.end();
            }
            regex.append(Pattern.quote(path.substring(last)));
            routes.add(new Route(method, Pattern.compile(regex.toString()), names, h));
            return this;
        }

        public void start() { server.start(); }
        public void stop() { server.stop(0); pool.shutdownNow(); }
        public int port() { return server.getAddress().getPort(); }

        private void dispatch(HttpExchange ex) throws IOException {
            Response resp;
            try {
                resp = handle(ex);
            } catch (ApiException e) {
                resp = e.response;
            } catch (Exception e) {
                Log.warn(tag, "Unhandled error on %s %s: %s", ex.getRequestMethod(), ex.getRequestURI().getPath(), e);
                resp = Response.problem(500, "internal-error", "Internal error", e.getClass().getSimpleName());
            }
            try {
                Headers h = ex.getResponseHeaders();
                h.set("Content-Type", resp.contentType);
                h.set("Cache-Control", "no-store");
                resp.headers.forEach(h::set);
                byte[] body = resp.body;
                ex.sendResponseHeaders(resp.status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) ex.getResponseBody().write(body);
            } catch (IOException ignored) {
                // client went away (e.g. its timeout fired first) – that is expected in chaos scenarios
            } finally {
                ex.close();
            }
        }

        private Response handle(HttpExchange ex) throws Exception {
            String path = ex.getRequestURI().getPath();
            boolean pathMatched = false;
            for (Route r : routes) {
                Matcher m = r.pattern.matcher(path);
                if (!m.matches()) continue;
                pathMatched = true;
                if (!r.method.equals(ex.getRequestMethod())) continue;
                Map<String, String> params = new LinkedHashMap<>();
                for (int i = 0; i < r.names.size(); i++)
                    params.put(r.names.get(i), URLDecoder.decode(m.group(i + 1), StandardCharsets.UTF_8));
                String body;
                try (InputStream in = ex.getRequestBody()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                return r.handler.handle(new Request(ex.getRequestMethod(), path, params,
                        parseQuery(ex.getRequestURI().getRawQuery()), ex.getRequestHeaders(), body));
            }
            return pathMatched
                    ? Response.problem(405, "method-not-allowed", "Method not allowed", null)
                    : Response.problem(404, "not-found", "Not found", path);
        }
    }

    public static final class Request {
        public final String method, path, body;
        public final Map<String, String> params, query;
        private final Headers headers;
        private Map<String, Object> json;

        Request(String method, String path, Map<String, String> params, Map<String, String> query, Headers headers, String body) {
            this.method = method; this.path = path; this.params = params; this.query = query;
            this.headers = headers; this.body = body;
        }

        public String header(String name) { return headers.getFirst(name); }
        public String param(String name) { return params.get(name); }

        public Map<String, Object> json() {
            if (json == null) {
                try { json = Json.parseObject(body); }
                catch (IllegalArgumentException e) {
                    throw new ApiException(Response.problem(400, "invalid-json", "Malformed JSON body", e.getMessage()));
                }
            }
            return json;
        }

        public Map<String, String> form() { return parseQuery(body); }

        public String bearer() {
            String h = header("Authorization");
            if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
            return h.substring(7).trim();
        }
    }

    public static final class Response {
        public final int status;
        public final byte[] body;
        public final String contentType;
        public final Map<String, String> headers = new LinkedHashMap<>();

        public Response(int status, byte[] body, String contentType) {
            this.status = status; this.body = body; this.contentType = contentType;
        }

        public static Response json(int status, Object o) {
            return new Response(status, Json.write(o).getBytes(StandardCharsets.UTF_8), "application/json");
        }

        public static Response xml(int status, String xml) {
            return new Response(status, xml.getBytes(StandardCharsets.UTF_8), "text/xml; charset=utf-8");
        }

        public static Response bytes(int status, byte[] body, String type) { return new Response(status, body, type); }

        public static Response empty(int status) { return new Response(status, new byte[0], "application/json"); }

        /** RFC 9457 problem details. */
        public static Response problem(int status, String type, String title, String detail, Object... extra) {
            Map<String, Object> p = Json.obj("type", "https://errors.meridian.example/" + type, "title", title, "status", status);
            if (detail != null) p.put("detail", detail);
            for (int i = 0; i + 1 < extra.length; i += 2) p.put((String) extra[i], extra[i + 1]);
            return new Response(status, Json.write(p).getBytes(StandardCharsets.UTF_8), "application/problem+json");
        }

        public Response header(String k, String v) { headers.put(k, v); return this; }
    }

    /** Throw from a handler to short-circuit with a specific response. */
    public static class ApiException extends RuntimeException {
        public final Response response;
        public ApiException(Response response) {
            super("HTTP " + response.status);
            this.response = response;
        }
    }

    // =================================================================== client
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public record Reply(int status, String body, Map<String, List<String>> headers) {
        public Map<String, Object> json() { return Json.parseObject(body); }
        public boolean ok() { return status >= 200 && status < 300; }
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet())
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            return null;
        }
    }

    /** Thrown when a call times out: the remote side may or may not have processed it. */
    public static final class Timeout extends IOException {
        public Timeout(String msg) { super(msg); }
    }

    public static Reply call(String method, String url, Map<String, String> headers, String body,
                             String contentType, Duration timeout) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        if (headers != null) headers.forEach(b::header);
        if (body != null) {
            b.header("Content-Type", contentType);
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body(), r.headers().map());
        } catch (HttpTimeoutException e) {
            throw new Timeout(method + " " + url + " timed out after " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    public static Reply getJson(String url, Map<String, String> headers, Duration timeout) throws IOException {
        return call("GET", url, headers, null, null, timeout);
    }

    public static Reply sendJson(String method, String url, Map<String, String> headers, Object body, Duration timeout) throws IOException {
        return call(method, url, headers, Json.write(body), "application/json", timeout);
    }

    public static Reply postForm(String url, Map<String, String> form, Duration timeout) throws IOException {
        return call("POST", url, null, formEncode(form), "application/x-www-form-urlencoded", timeout);
    }

    // =================================================================== utils
    public static String formEncode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (v == null) return;
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null || q.isBlank()) return m;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }
}
