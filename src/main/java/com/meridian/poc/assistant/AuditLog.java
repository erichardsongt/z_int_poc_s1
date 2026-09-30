package com.meridian.poc.assistant;

import com.meridian.poc.common.Json;
import com.meridian.poc.common.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Append-only, hash-chained audit trail (design §10). Each entry commits to the previous entry's hash,
 * so any edit or deletion breaks {@link #verify()}. Production streams these to Meridian's SIEM.
 * Entries carry references and pseudonyms only – never raw account data or card numbers.
 */
public final class AuditLog {
    public record Entry(long seq, String ts, String type, String conversationId, String sub,
                        Map<String, Object> detail, String prevHash, String hash) {
        Map<String, Object> toMap() {
            return Json.obj("seq", seq, "ts", ts, "type", type, "conversationId", conversationId, "sub", sub,
                    "detail", detail, "prevHash", prevHash.substring(0, 12), "hash", hash.substring(0, 12));
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void append(String type, String conversationId, String sub, Map<String, Object> detail) {
        String prev = entries.isEmpty() ? "0".repeat(64) : entries.get(entries.size() - 1).hash;
        long seq = entries.size() + 1L;
        String ts = Instant.now().toString();
        String hash = sha256(prev + "|" + seq + "|" + ts + "|" + type + "|" + conversationId + "|" + sub + "|" + Json.write(detail));
        entries.add(new Entry(seq, ts, type, conversationId, sub, detail, prev, hash));
        Log.info("AUDIT", "#%d %s %s", seq, type, Json.write(detail));
    }

    public synchronized boolean verify() {
        String prev = "0".repeat(64);
        for (Entry e : entries) {
            String h = sha256(prev + "|" + e.seq + "|" + e.ts + "|" + e.type + "|" + e.conversationId + "|" + e.sub + "|" + Json.write(e.detail));
            if (!e.prevHash.equals(prev) || !e.hash.equals(h)) return false;
            prev = e.hash;
        }
        return true;
    }

    public synchronized List<Object> tail(int n) {
        List<Object> out = new ArrayList<>();
        for (int i = Math.max(0, entries.size() - n); i < entries.size(); i++) out.add(entries.get(i).toMap());
        return out;
    }

    public synchronized int size() { return entries.size(); }

    public synchronized String allText() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(Json.write(e.toMap())).append('\n');
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
