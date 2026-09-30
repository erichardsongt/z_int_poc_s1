package com.meridian.poc.assistant;

import com.meridian.poc.Config;
import com.meridian.poc.common.Http;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Jwt;
import com.meridian.poc.common.Log;

import java.io.IOException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Salesforce REST client for the least-privilege integration user.
 * Auth: OAuth 2.0 JWT bearer flow (assertion signed with the connected app's certificate key).
 * Writes: upsert by external ID, so a retried create can never produce a second Case.
 */
public final class SalesforceClient {
    private final Config cfg;
    private final PrivateKey certKey;
    private volatile String accessToken;

    public SalesforceClient(Config cfg, PrivateKey certKey) { this.cfg = cfg; this.certKey = certKey; }

    public Map<String, Object> upsertCase(String disputeRef, Map<String, Object> fields) throws RemoteError {
        for (int authAttempt = 0; authAttempt < 2; authAttempt++) {
            String token = token();
            try {
                Http.Reply r = Http.sendJson("PATCH",
                        cfg.salesforceUrl() + "/services/data/v61.0/sobjects/Case/Dispute_Ref__c/" + disputeRef,
                        Map.of("Authorization", "Bearer " + token), fields, cfg.salesforceTimeout);
                if (r.status() == 401) { accessToken = null; continue; } // session expired: re-authenticate once
                if (!r.ok()) throw RemoteError.fromReply("Salesforce upsert Case", r);
                return r.json();
            } catch (IOException e) {
                throw RemoteError.transport("Salesforce upsert Case", e);
            }
        }
        throw new RemoteError("Salesforce authentication failed", 401, false, null);
    }

    public Map<String, Object> getCase(String disputeRef) throws RemoteError {
        try {
            Http.Reply r = Http.getJson(cfg.salesforceUrl() + "/services/data/v61.0/sobjects/Case/Dispute_Ref__c/" + disputeRef,
                    Map.of("Authorization", "Bearer " + token()), cfg.salesforceTimeout);
            if (!r.ok()) throw RemoteError.fromReply("Salesforce get Case", r);
            return r.json();
        } catch (IOException e) {
            throw RemoteError.transport("Salesforce get Case", e);
        }
    }

    private String token() throws RemoteError {
        String t = accessToken;
        if (t != null) return t;
        long now = Instant.now().getEpochSecond();
        String assertion = Jwt.sign(Json.obj("iss", Config.SF_CONSUMER_KEY, "sub", Config.SF_INTEGRATION_USER,
                "aud", cfg.salesforceUrl(), "exp", now + 180), certKey, "sf-connected-app");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.put("assertion", assertion);
        try {
            Http.Reply r = Http.postForm(cfg.salesforceUrl() + "/services/oauth2/token", form, cfg.salesforceTimeout);
            if (!r.ok()) throw RemoteError.fromReply("Salesforce JWT bearer", r);
            accessToken = Json.str(r.json(), "access_token");
            Log.info("ASSIST", "Salesforce session established (JWT bearer flow)");
            return accessToken;
        } catch (IOException e) {
            throw RemoteError.transport("Salesforce JWT bearer", e);
        }
    }
}
