package com.meridian.poc.bank;

import com.meridian.poc.common.Http;
import com.meridian.poc.common.Http.Response;
import com.meridian.poc.common.Json;
import com.meridian.poc.common.Log;
import com.meridian.poc.common.Soap;
import org.w3c.dom.Element;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated legacy core-banking platform exposing SOAP 1.1 services "behind the firewall".
 *
 * <p>Deliberately <b>not idempotent</b>: every RegisterDispute call creates a new record, like many
 * legacy cores. Preventing duplicates is the façade's job (read-before-retry on the client reference).</p>
 *
 * <p>Chaos: a scripted queue of behaviours (deterministic demos/tests) takes priority; otherwise the
 * mode decides — NORMAL, FLAKY (random failures and timeouts) or DOWN.</p>
 */
public final class CoreBankingSimulator {
    public enum Mode { NORMAL, FLAKY, DOWN }

    /** What the next RegisterDispute call does. */
    public enum Behaviour {
        OK,
        FAIL,                  // SOAP fault, nothing written
        TIMEOUT_AFTER_COMMIT,  // record IS written, then the response hangs past the caller's timeout
        TIMEOUT_NO_COMMIT      // hangs past the timeout, nothing written
    }

    public record Txn(String txnRef, String cardRef, String date, String merchant, String amount,
                      String currency, String status, String category) {}

    public record CoreDispute(String coreRef, String clientReference, String txnRef, String customerId,
                              String reasonCode, String notifiedAt, String createdAt) {}

    private static final long HANG_MS = 2500; // longer than the façade's core timeout

    private final Http.Server server;
    private volatile Mode mode = Mode.NORMAL;
    private final Deque<Behaviour> script = new ConcurrentLinkedDeque<>();
    private final Random rnd = new Random();
    private final List<CoreDispute> disputes = new CopyOnWriteArrayList<>();
    private final AtomicInteger registerCalls = new AtomicInteger();
    private final AtomicInteger seq = new AtomicInteger(40000);
    private final Map<String, Txn> txns = new LinkedHashMap<>();

    public CoreBankingSimulator(int port) throws IOException {
        seed();
        server = new Http.Server("CORE", port)
                .post("/soap/CoreBankingService/v3", this::soap)
                .get("/admin/state", r -> Response.json(200, state()))
                .post("/admin/chaos", r -> {
                    applyChaos(r.json());
                    return Response.json(200, state());
                });
    }

    private void seed() {
        add(new Txn("TXN-9A1F", "CARD-7F3A", "2026-09-24", "STREAMFLIX*EU PREMIUM", "89.99", "EUR", "POSTED", "Subscriptions"));
        add(new Txn("TXN-5C22", "CARD-7F3A", "2026-09-22", "GREENLEAF GROCERS BERLIN", "42.17", "EUR", "POSTED", "Groceries"));
        add(new Txn("TXN-77D0", "CARD-7F3A", "2026-09-19", "AIRLINK TRAVEL", "312.40", "EUR", "POSTED", "Travel"));
        add(new Txn("TXN-4E90", "CARD-7F3A", "2026-09-17", "CAFE LUMEN", "7.80", "EUR", "POSTED", "Dining"));
        add(new Txn("TXN-3B8E", "CARD-7F3A", "2026-09-29", "CITY PARKING APP", "6.50", "EUR", "PENDING", "Transport"));
        add(new Txn("TXN-1D55", "CARD-91BC", "2026-09-23", "NORDIC OUTDOOR GEAR", "154.00", "EUR", "POSTED", "Shopping"));
    }

    private void add(Txn t) { txns.put(t.txnRef(), t); }

    public void start() { server.start(); }
    public void stop() { server.stop(); }

    // ------------------------------------------------------------------ SOAP endpoint
    private Response soap(Http.Request req) throws InterruptedException {
        Element op;
        try {
            op = Soap.body(req.body);
        } catch (IllegalArgumentException e) {
            return Response.xml(500, Soap.fault("CLIENT_INVALID_REQUEST", e.getMessage()));
        }
        String name = op.getLocalName();
        if (mode == Mode.DOWN && !name.equals("RegisterDisputeRequest")) {
            Log.warn("CORE", "%s -> SOAP Fault CORE_UNAVAILABLE (core is DOWN)", name);
            return Response.xml(500, Soap.fault("CORE_UNAVAILABLE", "Core banking unavailable"));
        }
        return switch (name) {
            case "GetCardTransactionsRequest" -> getTransactions(op);
            case "FindDisputeRequest" -> findDispute(op);
            case "RegisterDisputeRequest" -> registerDispute(op);
            default -> Response.xml(500, Soap.fault("CLIENT_UNKNOWN_OPERATION", name));
        };
    }

    private Response getTransactions(Element op) {
        String customerId = Soap.text(op, "CustomerId");
        String cardRef = Soap.text(op, "CardRef");
        BankDirectory.Card card = BankDirectory.CARDS.get(cardRef);
        if (card == null || !card.customerId().equals(customerId))
            return Response.xml(500, Soap.fault("CLIENT_CARD_NOT_FOUND", "Card not found for customer"));
        List<Map<String, String>> items = new ArrayList<>();
        for (Txn t : txns.values()) {
            if (!t.cardRef().equals(cardRef)) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("TxnRef", t.txnRef()); m.put("PostingDate", t.date()); m.put("MerchantName", t.merchant());
            m.put("Amount", t.amount()); m.put("Currency", t.currency()); m.put("Status", t.status());
            m.put("Category", t.category());
            items.add(m);
        }
        Log.info("CORE", "GetCardTransactions %s -> %d rows", cardRef, items.size());
        return Response.xml(200, Soap.envelopeWithList("GetCardTransactionsResponse", "Transaction", items));
    }

    private Response findDispute(Element op) {
        String clientRef = Soap.text(op, "ClientReference");
        String txnRef = Soap.text(op, "TxnRef");
        for (CoreDispute d : disputes) {
            if ((clientRef != null && clientRef.equals(d.clientReference())) || (txnRef != null && txnRef.equals(d.txnRef()))) {
                return Response.xml(200, Soap.envelope("FindDisputeResponse", Map.of(
                        "Found", "true", "CoreDisputeRef", d.coreRef(), "ClientReference", d.clientReference())));
            }
        }
        return Response.xml(200, Soap.envelope("FindDisputeResponse", Map.of("Found", "false")));
    }

    private Response registerDispute(Element op) throws InterruptedException {
        registerCalls.incrementAndGet();
        Map<String, String> f = Soap.fields(op);
        Txn t = txns.get(f.get("TxnRef"));
        if (t == null || !t.cardRef().equals(f.get("CardRef")))
            return Response.xml(500, Soap.fault("CLIENT_TXN_NOT_FOUND", "Transaction not found on card"));
        Behaviour b = nextBehaviour();
        switch (b) {
            case FAIL -> {
                Log.warn("CORE", "RegisterDispute(%s) -> SOAP Fault CORE_UNAVAILABLE  [chaos: %s]", f.get("ClientReference"), b);
                return Response.xml(500, Soap.fault("CORE_UNAVAILABLE", "Dispute subsystem unavailable"));
            }
            case TIMEOUT_NO_COMMIT -> {
                Log.warn("CORE", "RegisterDispute(%s) hanging, nothing written  [chaos: %s]", f.get("ClientReference"), b);
                Thread.sleep(HANG_MS);
                return Response.xml(500, Soap.fault("CORE_TIMEOUT", "Timed out"));
            }
            default -> { }
        }
        CoreDispute d = new CoreDispute("CBD-" + seq.incrementAndGet(), f.get("ClientReference"), f.get("TxnRef"),
                f.get("CustomerId"), f.get("ReasonCode"), f.get("NotifiedAt"), Instant.now().toString());
        disputes.add(d);
        if (b == Behaviour.TIMEOUT_AFTER_COMMIT) {
            Log.warn("CORE", "RegisterDispute(%s) WROTE %s, then response hangs  [chaos: %s]", d.clientReference(), d.coreRef(), b);
            Thread.sleep(HANG_MS);
        } else {
            Log.info("CORE", "RegisterDispute(%s) -> %s", d.clientReference(), d.coreRef());
        }
        return Response.xml(200, Soap.envelope("RegisterDisputeResponse", Map.of(
                "CoreDisputeRef", d.coreRef(), "Status", "REGISTERED")));
    }

    private Behaviour nextBehaviour() {
        Behaviour scripted = script.pollFirst();
        if (scripted != null) return scripted;
        return switch (mode) {
            case NORMAL -> Behaviour.OK;
            case DOWN -> Behaviour.FAIL;
            case FLAKY -> {
                int r = rnd.nextInt(100);
                yield r < 45 ? Behaviour.OK : r < 75 ? Behaviour.FAIL : Behaviour.TIMEOUT_AFTER_COMMIT;
            }
        };
    }

    // ------------------------------------------------------------------ admin / chaos
    public void applyChaos(Map<String, Object> body) {
        String m = Json.str(body, "mode");
        if (m != null) {
            mode = Mode.valueOf(m);
            Log.warn("CHAOS", "core banking mode -> %s", mode);
        }
        if (body.containsKey("script")) {
            script.clear();
            for (Object o : Json.list(body, "script")) script.add(Behaviour.valueOf(o.toString()));
            Log.warn("CHAOS", "core banking next RegisterDispute calls -> %s", script);
        }
    }

    public Map<String, Object> state() {
        Map<String, Long> perClientRef = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        for (CoreDispute d : disputes) {
            perClientRef.merge(d.clientReference(), 1L, Long::sum);
            rows.add(Json.obj("coreRef", d.coreRef(), "clientReference", d.clientReference(), "txnRef", d.txnRef(),
                    "reasonCode", d.reasonCode(), "createdAt", d.createdAt()));
        }
        long duplicates = perClientRef.values().stream().filter(c -> c > 1).count();
        return Json.obj("mode", mode, "script", new ArrayList<>(script), "registerCalls", registerCalls.get(),
                "disputeRecords", rows, "clientReferencesWithDuplicates", duplicates);
    }

    public long recordsFor(String clientReference) {
        return disputes.stream().filter(d -> clientReference.equals(d.clientReference())).count();
    }
}
