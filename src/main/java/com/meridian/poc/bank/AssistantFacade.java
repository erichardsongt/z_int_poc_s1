package com.meridian.poc.bank;

import com.meridian.poc.Config;
import com.meridian.poc.common.Http;
import com.meridian.poc.common.Http.ApiException;
import com.meridian.poc.common.Http.Response;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Log;
import com.meridian.poc.common.Soap;
import com.meridian.poc.security.TokenValidator;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Meridian's Assistant API façade (bank-owned, in the DMZ). The only route from the assistant to
 * core banking. Responsibilities shown here:
 * <ul>
 *   <li>validate the delegated token (audience = façade, actor = assistant data plane);</li>
 *   <li>map the pairwise subject to a customer and enforce ownership of cards/transactions;</li>
 *   <li>field allow-list: only opaque refs and what the assistant needs, never a PAN;</li>
 *   <li>REST ↔ SOAP translation with a hard timeout towards the core;</li>
 *   <li>idempotent dispute registration on a non-idempotent core: Idempotency-Key replay store +
 *       <b>read-before-write</b> on the client reference + one-open-dispute-per-transaction rule.</li>
 * </ul>
 */
public final class AssistantFacade {
    private static final String SCOPE_TXN_READ = "assistant.transactions.read";
    private static final String SCOPE_DISPUTE_WRITE = "assistant.disputes.write";
    private static final String SCOPE_DISPUTE_CONTINUE = "facade.disputes.continue";

    private record Stored(String requestHash, int status, Map<String, Object> body) {}

    private final Config cfg;
    private final BankDirectory directory;
    private final TokenValidator validator;
    private final Http.Server server;
    private final Map<String, Stored> idempotency = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public AssistantFacade(Config cfg, BankDirectory directory) throws IOException {
        this.cfg = cfg;
        this.directory = directory;
        this.validator = new TokenValidator(cfg.idpIssuer(), Config.AUD_FACADE, cfg.idpJwks(), "meridian-assistant-facade");
        this.server = new Http.Server("FACADE", cfg.facadePort)
                .get("/v1/cards", this::cards)
                .get("/v1/cards/{cardRef}/transactions", this::transactions)
                .post("/v1/disputes", this::registerDispute)
                .get("/admin/state", r -> Response.json(200, Json.obj("idempotencyKeys", idempotency.size())));
    }

    public void start() { server.start(); }
    public void stop() { server.stop(); }

    // ------------------------------------------------------------------ auth helpers
    /** Delegated call: token issued to the data plane on behalf of a customer (RFC 8693 act claim). */
    private String delegatedCustomer(Http.Request req, String scope) {
        Map<String, Object> c = validator.validate(req.bearer());
        Map<String, Object> act = Json.map(c, "act");
        if (act == null || !Config.CLIENT_DATAPLANE.equals(act.get("sub")))
            throw new ApiException(Response.problem(403, "actor-not-allowed", "Forbidden", "token must be delegated to the assistant data plane"));
        validator.requireScope(c, scope);
        String customer = directory.customerForPairwise(Json.str(c, "sub"));
        if (customer == null) throw new ApiException(Response.problem(403, "unknown-subject", "Forbidden", "unknown customer"));
        return customer;
    }

    // ------------------------------------------------------------------ reads
    private Response cards(Http.Request req) {
        String customer = delegatedCustomer(req, SCOPE_TXN_READ);
        List<Object> out = new ArrayList<>();
        for (String ref : BankDirectory.CUSTOMERS.get(customer).cardRefs()) {
            BankDirectory.Card c = BankDirectory.CARDS.get(ref);
            out.add(Json.obj("cardRef", c.cardRef(), "display", c.masked(), "product", c.product()));
        }
        return Response.json(200, Json.obj("cards", out));
    }

    private Response transactions(Http.Request req) throws IOException {
        String customer = delegatedCustomer(req, SCOPE_TXN_READ);
        String cardRef = req.param("cardRef");
        requireCardOwnership(customer, cardRef);
        Element resp = callCore("GetCardTransactionsRequest", Map.of("CustomerId", customer, "CardRef", cardRef));
        List<Object> out = new ArrayList<>();
        for (Map<String, String> t : Soap.items(resp, "Transaction")) {
            // Field allow-list: exactly what the assistant needs, nothing more.
            out.add(Json.obj("txnRef", t.get("TxnRef"), "date", t.get("PostingDate"), "merchant", t.get("MerchantName"),
                    "amount", Json.obj("value", t.get("Amount"), "currency", t.get("Currency")),
                    "status", t.get("Status"), "category", t.get("Category"),
                    "disputable", "POSTED".equals(t.get("Status"))));
        }
        return Response.json(200, Json.obj("cardRef", cardRef, "transactions", out));
    }

    private void requireCardOwnership(String customer, String cardRef) {
        BankDirectory.Card c = BankDirectory.CARDS.get(cardRef);
        if (c == null || !c.customerId().equals(customer))
            throw new ApiException(Response.problem(404, "card-not-found", "Card not found", null)); // 404, not 403: don't leak existence
    }

    // ------------------------------------------------------------------ dispute registration
    private Response registerDispute(Http.Request req) throws IOException {
        Map<String, Object> body = req.json();
        String customer;
        Map<String, Object> claims = validator.validate(req.bearer());
        if (TokenValidator.hasScope(claims, SCOPE_DISPUTE_CONTINUE)) {
            // Asynchronous continuation of an instruction the customer already confirmed (their token has expired).
            if (!Config.CLIENT_DATAPLANE.equals(claims.get("sub")))
                throw new ApiException(Response.problem(403, "client-not-allowed", "Forbidden", null));
            Map<String, Object> consent = Json.map(body, "consent");
            if (consent == null || consent.get("confirmedAt") == null || consent.get("customerTokenId") == null)
                throw new ApiException(Response.problem(400, "consent-required", "Confirmed-instruction evidence required", null));
            customer = directory.customerForPairwise(Json.str(body, "onBehalfOf"));
            if (customer == null) throw new ApiException(Response.problem(403, "unknown-subject", "Forbidden", null));
        } else {
            customer = delegatedCustomer(req, SCOPE_DISPUTE_WRITE);
        }

        String key = req.header("Idempotency-Key");
        if (key == null || key.isBlank())
            throw new ApiException(Response.problem(400, "idempotency-key-required", "Idempotency-Key header required", null));
        String txnRef = Json.str(body, "txnRef"), cardRef = Json.str(body, "cardRef"), reason = Json.str(body, "reasonCode");
        if (txnRef == null || cardRef == null || reason == null)
            throw new ApiException(Response.problem(400, "invalid-request", "txnRef, cardRef and reasonCode are required", null));
        requireCardOwnership(customer, cardRef);
        String hash = sha256(customer + "|" + cardRef + "|" + txnRef + "|" + reason);

        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            Stored prior = idempotency.get(key);
            if (prior != null) {
                if (!prior.requestHash.equals(hash))
                    return Response.problem(422, "idempotency-key-reuse", "Idempotency-Key reused with a different payload", null);
                Log.info("FACADE", "Idempotency-Key %s seen before -> replaying stored response", key);
                return Response.json(prior.status, prior.body).header("Idempotent-Replayed", "true");
            }

            // 1) Read-before-write: did an earlier attempt (whose response we lost) already land in core?
            Element found = callCore("FindDisputeRequest", Map.of("ClientReference", key));
            if ("true".equals(Soap.text(found, "Found"))) {
                String coreRef = Soap.text(found, "CoreDisputeRef");
                Log.info("FACADE", "read-before-write: %s already registered as %s -> no second write", key, coreRef);
                return store(key, hash, 201, Json.obj("coreDisputeRef", coreRef, "status", "REGISTERED",
                        "clientReference", key, "recoveredFromPriorAttempt", true));
            }
            // 2) Business rule: one open dispute per transaction, whoever/whatever submitted it.
            Element byTxn = callCore("FindDisputeRequest", Map.of("TxnRef", txnRef));
            if ("true".equals(Soap.text(byTxn, "Found"))) {
                return store(key, hash, 409, Json.obj("type", "https://errors.meridian.example/dispute-exists",
                        "title", "An open dispute already exists for this transaction", "status", 409,
                        "existingCoreDisputeRef", Soap.text(byTxn, "CoreDisputeRef"),
                        "existingClientReference", Soap.text(byTxn, "ClientReference")));
            }
            // 3) Write.
            Element reg = callCore("RegisterDisputeRequest", Map.of("CustomerId", customer, "CardRef", cardRef,
                    "TxnRef", txnRef, "ReasonCode", reason, "ClientReference", key,
                    "NotifiedAt", String.valueOf(Json.str(body, "notifiedAt"))));
            String coreRef = Soap.text(reg, "CoreDisputeRef");
            Log.info("FACADE", "dispute %s registered in core as %s", key, coreRef);
            return store(key, hash, 201, Json.obj("coreDisputeRef", coreRef, "status", "REGISTERED",
                    "clientReference", key, "recoveredFromPriorAttempt", false));
        }
    }

    private Response store(String key, String hash, int status, Map<String, Object> body) {
        idempotency.put(key, new Stored(hash, status, body));
        return Response.json(status, body);
    }

    // ------------------------------------------------------------------ core call (REST -> SOAP)
    private Element callCore(String op, Map<String, String> fields) throws IOException {
        Http.Reply r;
        try {
            r = Http.call("POST", cfg.coreUrl() + "/soap/CoreBankingService/v3",
                    Map.of("SOAPAction", "urn:meridian:corebanking:v3#" + op), Soap.envelope(op, fields),
                    "text/xml; charset=utf-8", cfg.coreCallTimeout);
        } catch (Http.Timeout t) {
            Log.warn("FACADE", "%s timed out after %d ms -> 504 (outcome unknown)", op, cfg.coreCallTimeout.toMillis());
            throw new ApiException(Response.problem(504, "core-timeout", "Core banking did not answer in time",
                    "Outcome unknown: retry with the same Idempotency-Key", "retryable", true));
        } catch (IOException e) {
            throw new ApiException(Response.problem(503, "core-unavailable", "Core banking unreachable", null, "retryable", true));
        }
        Element el = Soap.body(r.body());
        if (Soap.isFault(el)) {
            String code = Soap.text(el, "faultstring");
            if (code != null && code.startsWith("CLIENT_"))
                throw new ApiException(Response.problem(422, "core-rejected", "Core banking rejected the request", code, "retryable", false));
            throw new ApiException(Response.problem(503, "core-unavailable", "Core banking unavailable", code, "retryable", true)
                    .header("Retry-After", "2"));
        }
        return el;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
