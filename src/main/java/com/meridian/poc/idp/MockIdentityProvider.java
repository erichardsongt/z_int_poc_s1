package com.meridian.poc.idp;

import com.meridian.poc.Config;
import com.meridian.poc.bank.BankDirectory;
import com.meridian.poc.common.Http;
import com.meridian.poc.common.Http.Response;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Jwt;
import com.meridian.poc.common.Log;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for Meridian's OAuth 2.0 / OIDC authorization server.
 * <ul>
 *   <li>{@code /demo/app-login}: simulates the customer's existing mobile-app login (Auth Code + PKCE + SCA)
 *       by minting the app's access token directly;</li>
 *   <li>{@code /oauth2/token} with {@code grant_type=urn:ietf:params:oauth:grant-type:token-exchange} (RFC 8693):
 *       app token → AT1 (audience = assistant), and AT1 → AT2 (audience = façade, {@code act} = data plane);</li>
 *   <li>{@code client_credentials} for the data plane's asynchronous dispute continuation;</li>
 *   <li>confidential-client authentication via {@code private_key_jwt} (RFC 7523) with jti replay protection;</li>
 *   <li>JWKS publication for resource servers.</li>
 * </ul>
 * Not implemented (noted in README): DPoP / mTLS sender-constraining, refresh tokens, back-channel logout.
 */
public final class MockIdentityProvider {
    private static final String GT_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String TT_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final Set<String> ASSISTANT_SCOPES = Set.of(
            "assistant.accounts.read", "assistant.transactions.read", "assistant.disputes.write", "assistant.handoff");

    private final Config cfg;
    private final BankDirectory directory;
    private final KeyPair signing = Jwt.newRsaKeyPair();
    private final String kid = "meridian-idp-2026-09";
    private final Map<String, PublicKey> registeredClientKeys = new ConcurrentHashMap<>();
    private final Set<String> usedAssertionIds = ConcurrentHashMap.newKeySet();
    private final Http.Server server;

    public MockIdentityProvider(Config cfg, BankDirectory directory) throws IOException {
        this.cfg = cfg;
        this.directory = directory;
        this.server = new Http.Server("IDP", cfg.idpPort)
                .get("/.well-known/jwks.json", r -> Response.json(200,
                        Json.obj("keys", List.of(Jwt.toJwk((RSAPublicKey) signing.getPublic(), kid)))))
                .post("/demo/app-login", this::appLogin)
                .post("/oauth2/token", this::token);
    }

    /** Out-of-band client registration: the data plane's public key for private_key_jwt. */
    public void registerClientKey(String clientId, PublicKey key) { registeredClientKeys.put(clientId, key); }

    public void start() { server.start(); }
    public void stop() { server.stop(); }

    // ------------------------------------------------------------------ simulated app login
    private Response appLogin(Http.Request req) {
        Map<String, Object> b = req.json();
        String customerId = Json.str(b, "customerId");
        String acr = b.getOrDefault("acr", "low").toString();
        if (!BankDirectory.CUSTOMERS.containsKey(customerId)) return oauthError(400, "invalid_request", "unknown customer");
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = Json.obj("iss", cfg.idpIssuer(), "sub", customerId, "aud", Config.AUD_MOBILE,
                "client_id", Config.CLIENT_MOBILE, "acr", acr, "amr", List.of("hwk", "high".equals(acr) ? "face" : "pin"),
                "auth_time", now, "sid", "sid-" + UUID.randomUUID().toString().substring(0, 8),
                "scope", "openid accounts.read cards.read assistant", "iat", now, "exp", now + 900,
                "jti", UUID.randomUUID().toString());
        Log.info("IDP", "app login for %s (acr=%s) -> mobile access token", customerId, acr);
        return Response.json(200, Json.obj("access_token", Jwt.sign(claims, signing.getPrivate(), kid),
                "token_type", "Bearer", "expires_in", 900));
    }

    // ------------------------------------------------------------------ token endpoint
    private Response token(Http.Request req) {
        Map<String, String> f = req.form();
        String grant = f.get("grant_type");
        if (GT_TOKEN_EXCHANGE.equals(grant)) return tokenExchange(f);
        if ("client_credentials".equals(grant)) return clientCredentials(f);
        return oauthError(400, "unsupported_grant_type", grant);
    }

    private Response tokenExchange(Map<String, String> f) {
        if (!TT_ACCESS_TOKEN.equals(f.get("subject_token_type")))
            return oauthError(400, "invalid_request", "subject_token_type must be access_token");
        Map<String, Object> subject;
        try {
            subject = Jwt.verify(f.get("subject_token"), k -> kid.equals(k) ? signing.getPublic() : null);
        } catch (Jwt.JwtException e) {
            return oauthError(400, "invalid_grant", "subject_token: " + e.getMessage());
        }
        long now = Instant.now().getEpochSecond();
        if (((Number) subject.get("exp")).longValue() < now) return oauthError(400, "invalid_grant", "subject_token expired");
        String audience = f.get("audience");

        if (Config.AUD_ASSISTANT.equals(audience)) {
            // Step 1: the mobile app (public client) exchanges its own token for an assistant-audience token.
            if (!Config.CLIENT_MOBILE.equals(f.get("client_id")) || !Config.AUD_MOBILE.equals(subject.get("aud")))
                return oauthError(400, "invalid_grant", "only the mobile app may exchange a mobile token");
            Set<String> scopes = intersect(f.get("scope"), ASSISTANT_SCOPES);
            if (scopes.isEmpty()) return oauthError(400, "invalid_scope", "no permitted scopes requested");
            String customerId = Json.str(subject, "sub");
            Map<String, Object> at1 = Json.obj("iss", cfg.idpIssuer(),
                    "sub", directory.pairwiseFor(customerId, Config.AUD_ASSISTANT), // assistant never sees the customer number
                    "aud", Config.AUD_ASSISTANT, "azp", Config.CLIENT_MOBILE,
                    "acr", subject.get("acr"), "auth_time", subject.get("auth_time"), "sid", subject.get("sid"),
                    "scope", String.join(" ", scopes), "iat", now, "exp", now + 600, "jti", UUID.randomUUID().toString());
            Log.info("IDP", "token-exchange: mobile token -> AT1 (aud=%s, sub=%s, scope=%s)", Config.AUD_ASSISTANT, at1.get("sub"), at1.get("scope"));
            return issued(at1, 600);
        }

        if (Config.AUD_FACADE.equals(audience)) {
            // Step 2: the data plane (confidential client) exchanges AT1 for a façade token that records it as actor.
            String clientErr = authenticateClient(f);
            if (clientErr != null) return oauthError(401, "invalid_client", clientErr);
            if (!Config.AUD_ASSISTANT.equals(subject.get("aud")))
                return oauthError(400, "invalid_grant", "subject_token must be an assistant token");
            Set<String> granted = intersect(Json.str(subject, "scope"), ASSISTANT_SCOPES);
            Set<String> requested = intersect(f.get("scope"), granted);
            if (f.get("scope") != null && requested.size() != f.get("scope").split(" ").length)
                return oauthError(400, "invalid_scope", "cannot broaden scope beyond the subject token");
            Map<String, Object> at2 = Json.obj("iss", cfg.idpIssuer(), "sub", subject.get("sub"),
                    "aud", Config.AUD_FACADE, "act", Json.obj("sub", Config.CLIENT_DATAPLANE),
                    "acr", subject.get("acr"), "auth_time", subject.get("auth_time"),
                    "scope", String.join(" ", requested.isEmpty() ? granted : requested),
                    "iat", now, "exp", now + 300, "jti", UUID.randomUUID().toString());
            Log.info("IDP", "token-exchange: AT1 -> AT2 (aud=%s, act=%s, scope=%s)", Config.AUD_FACADE, Config.CLIENT_DATAPLANE, at2.get("scope"));
            return issued(at2, 300);
        }
        return oauthError(400, "invalid_target", "unknown audience " + audience);
    }

    private Response clientCredentials(Map<String, String> f) {
        String clientErr = authenticateClient(f);
        if (clientErr != null) return oauthError(401, "invalid_client", clientErr);
        if (!"facade.disputes.continue".equals(f.get("scope"))) return oauthError(400, "invalid_scope", f.get("scope"));
        long now = Instant.now().getEpochSecond();
        Map<String, Object> t = Json.obj("iss", cfg.idpIssuer(), "sub", Config.CLIENT_DATAPLANE, "aud", Config.AUD_FACADE,
                "scope", "facade.disputes.continue", "iat", now, "exp", now + 300, "jti", UUID.randomUUID().toString());
        Log.info("IDP", "client_credentials -> service token (scope=facade.disputes.continue)");
        return issued(t, 300);
    }

    /** private_key_jwt (RFC 7523 §2.2): signed assertion, iss=sub=client_id, aud=token endpoint, single-use jti. */
    private String authenticateClient(Map<String, String> f) {
        if (!ASSERTION_TYPE.equals(f.get("client_assertion_type"))) return "private_key_jwt required";
        String clientId = f.get("client_id");
        PublicKey key = registeredClientKeys.get(clientId);
        if (key == null) return "unknown client";
        Map<String, Object> a;
        try {
            a = Jwt.verify(f.get("client_assertion"), k -> key);
        } catch (Jwt.JwtException e) {
            return "client_assertion " + e.getMessage();
        }
        if (!clientId.equals(a.get("iss")) || !clientId.equals(a.get("sub"))) return "assertion iss/sub mismatch";
        if (!cfg.idpTokenEndpoint().equals(a.get("aud"))) return "assertion audience mismatch";
        if (((Number) a.get("exp")).longValue() < Instant.now().getEpochSecond()) return "assertion expired";
        if (!usedAssertionIds.add(Json.str(a, "jti"))) return "assertion replayed";
        return null;
    }

    private Response issued(Map<String, Object> claims, int expiresIn) {
        return Response.json(200, Json.obj("access_token", Jwt.sign(claims, signing.getPrivate(), kid),
                "issued_token_type", TT_ACCESS_TOKEN, "token_type", "Bearer", "expires_in", expiresIn, "scope", claims.get("scope")));
    }

    private static Set<String> intersect(String requested, Set<String> allowed) {
        Set<String> out = new LinkedHashSet<>();
        if (requested == null) return out;
        Arrays.stream(requested.split(" ")).filter(allowed::contains).forEach(out::add);
        return out;
    }

    private static Response oauthError(int status, String error, String description) {
        return Response.json(status, Json.obj("error", error, "error_description", description));
    }
}
