package com.meridian.poc.bank;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bank-side reference data shared by the mock IdP and the façade (both live inside Meridian's perimeter).
 * Also owns the pairwise-subject mapping: the assistant only ever sees {@code pw_...} identifiers,
 * and only bank-side components can map them back to a customer number.
 */
public final class BankDirectory {
    public record Customer(String customerId, String displayName, List<String> cardRefs) {}
    public record Card(String cardRef, String customerId, String masked, String product) {}

    public static final Map<String, Customer> CUSTOMERS = Map.of(
            "C-100234", new Customer("C-100234", "Anna K.", List.of("CARD-7F3A")),
            "C-100877", new Customer("C-100877", "Luca M.", List.of("CARD-91BC")));

    public static final Map<String, Card> CARDS = Map.of(
            "CARD-7F3A", new Card("CARD-7F3A", "C-100234", "•• 4821", "Meridian Visa Debit"),
            "CARD-91BC", new Card("CARD-91BC", "C-100877", "•• 1177", "Meridian Mastercard Credit"));

    private final byte[] pairwiseSecret = new byte[32];
    private final Map<String, String> pairwiseIndex = new ConcurrentHashMap<>();

    public BankDirectory() { new SecureRandom().nextBytes(pairwiseSecret); }

    /** OIDC-style pairwise identifier: stable per (sector, customer), meaningless outside the bank. */
    public String pairwiseFor(String customerId, String sector) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pairwiseSecret, "HmacSHA256"));
            byte[] h = mac.doFinal((sector + "|" + customerId).getBytes(StandardCharsets.UTF_8));
            String sub = "pw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 22);
            pairwiseIndex.put(sub, customerId);
            return sub;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String customerForPairwise(String sub) { return sub == null ? null : pairwiseIndex.get(sub); }
}
