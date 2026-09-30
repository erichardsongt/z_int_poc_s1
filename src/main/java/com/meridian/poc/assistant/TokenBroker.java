package com.meridian.poc.assistant;

import com.meridian.poc.Config;
import com.meridian.poc.common.Http;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Jwt;
import com.meridian.poc.common.Log;

import java.io.IOException;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Obtains façade-audience tokens for the data plane (design §5):
 * <ul>
 *   <li>{@link #delegated}: RFC 8693 exchange of the customer's AT1 for AT2 (act = data plane), cached until near expiry;</li>
 *   <li>{@link #service}: client_credentials token used only to continue an already-confirmed dispute
 *       asynchronously, when the customer's token has long expired.</li>
 * </ul>
 * The data plane authenticates with private_key_jwt – no shared secret exists.
 */
public final class TokenBroker {
    private record Cached(String token, Instant expiresAt) {}

    private final Config cfg;
    private final PrivateKey clientKey;
    private final String kid;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TokenBroker(Config cfg, PrivateKey clientKey, String kid) {
        this.cfg = cfg; this.clientKey = clientKey; this.kid = kid;
    }

    public String delegated(String at1, Map<String, Object> at1Claims, String scope) throws RemoteError {
        String cacheKey = at1Claims.get("jti") + "|" + scope;
        Cached c = cache.get(cacheKey);
        if (c != null && c.expiresAt.isAfter(Instant.now().plusSeconds(30))) return c.token;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        form.put("subject_token", at1);
        form.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        form.put("audience", Config.AUD_FACADE);
        form.put("scope", scope);
        clientAuth(form);
        String token = request(form, "token exchange (AT1 -> AT2)");
        cache.put(cacheKey, new Cached(token, Instant.now().plusSeconds(290)));
        return token;
    }

    public String service() throws RemoteError {
        Cached c = cache.get("service");
        if (c != null && c.expiresAt.isAfter(Instant.now().plusSeconds(30))) return c.token;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("scope", "facade.disputes.continue");
        clientAuth(form);
        String token = request(form, "client_credentials");
        cache.put("service", new Cached(token, Instant.now().plusSeconds(290)));
        return token;
    }

    /** RFC 7523 client assertion: short-lived, single-use, audience-restricted to the token endpoint. */
    private void clientAuth(Map<String, String> form) {
        long now = Instant.now().getEpochSecond();
        String assertion = Jwt.sign(Json.obj("iss", Config.CLIENT_DATAPLANE, "sub", Config.CLIENT_DATAPLANE,
                "aud", cfg.idpTokenEndpoint(), "jti", UUID.randomUUID().toString(), "iat", now, "exp", now + 60), clientKey, kid);
        form.put("client_id", Config.CLIENT_DATAPLANE);
        form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.put("client_assertion", assertion);
    }

    private String request(Map<String, String> form, String what) throws RemoteError {
        try {
            Http.Reply r = Http.postForm(cfg.idpTokenEndpoint(), form, Duration.ofSeconds(2));
            if (!r.ok()) throw RemoteError.fromReply(what, r);
            Log.info("ASSIST", "%s OK", what);
            return Json.str(r.json(), "access_token");
        } catch (IOException e) {
            throw RemoteError.transport(what, e);
        }
    }
}
