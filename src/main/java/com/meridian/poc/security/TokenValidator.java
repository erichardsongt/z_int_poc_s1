package com.meridian.poc.security;

import com.meridian.poc.common.Http;
import com.meridian.poc.common.Http.ApiException;
import com.meridian.poc.common.Http.Response;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Jwt;

import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource-server token validation, as done by both the assistant data plane (audience
 * {@code meridian-assistant}) and the bank façade (audience {@code meridian-assistant-facade}).
 * Keys come from the IdP's JWKS endpoint and are cached by {@code kid}; an unknown kid triggers
 * one refresh (key rotation).
 */
public final class TokenValidator {
    private static final Duration SKEW = Duration.ofSeconds(30);

    private final String issuer, audience, jwksUrl, realm;
    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();

    public TokenValidator(String issuer, String audience, String jwksUrl, String realm) {
        this.issuer = issuer; this.audience = audience; this.jwksUrl = jwksUrl; this.realm = realm;
    }

    public Map<String, Object> validate(String token) {
        if (token == null) throw unauthorized("invalid_request", "Bearer token required");
        Map<String, Object> c;
        try {
            c = Jwt.verify(token, this::key);
        } catch (Jwt.JwtException e) {
            throw unauthorized("invalid_token", e.getMessage());
        }
        if (!issuer.equals(c.get("iss"))) throw unauthorized("invalid_token", "untrusted issuer");
        if (!audienceMatches(c.get("aud"))) throw unauthorized("invalid_token", "wrong audience (expected " + audience + ")");
        long now = Instant.now().getEpochSecond();
        Object exp = c.get("exp");
        if (!(exp instanceof Number n) || n.longValue() + SKEW.toSeconds() < now) throw unauthorized("invalid_token", "token expired");
        Object nbf = c.get("nbf");
        if (nbf instanceof Number nb && nb.longValue() - SKEW.toSeconds() > now) throw unauthorized("invalid_token", "token not yet valid");
        return c;
    }

    public static boolean hasScope(Map<String, Object> claims, String scope) {
        String s = Json.str(claims, "scope");
        return s != null && Arrays.asList(s.split(" ")).contains(scope);
    }

    public void requireScope(Map<String, Object> claims, String scope) {
        if (!hasScope(claims, scope)) {
            throw new ApiException(Response.problem(403, "insufficient-scope", "Insufficient scope", "requires " + scope)
                    .header("WWW-Authenticate", "Bearer realm=\"" + realm + "\", error=\"insufficient_scope\", scope=\"" + scope + "\""));
        }
    }

    private boolean audienceMatches(Object aud) {
        if (aud instanceof String s) return audience.equals(s);
        if (aud instanceof List<?> l) return l.contains(audience);
        return false;
    }

    private PublicKey key(String kid) {
        PublicKey k = keys.get(kid);
        if (k == null) { refresh(); k = keys.get(kid); }
        return k;
    }

    private synchronized void refresh() {
        try {
            Http.Reply r = Http.getJson(jwksUrl, null, Duration.ofSeconds(2));
            for (Object o : Json.list(r.json(), "keys")) {
                @SuppressWarnings("unchecked") Map<String, Object> jwk = (Map<String, Object>) o;
                keys.put(Json.str(jwk, "kid"), Jwt.fromJwk(jwk));
            }
        } catch (Exception e) {
            // IdP unreachable: tokens signed with cached keys still validate; unknown kids fail closed.
        }
    }

    private ApiException unauthorized(String error, String description) {
        return new ApiException(Response.problem(401, "unauthorized", "Unauthorized", description)
                .header("WWW-Authenticate", "Bearer realm=\"" + realm + "\", error=\"" + error
                        + "\", error_description=\"" + description + "\""));
    }
}
