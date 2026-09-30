package com.meridian.poc.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Console logging with a coloured tag per component, so the demo output reads like a trace. */
public final class Log {
    private Log() {}

    private static volatile boolean quiet = false;
    private static final boolean COLOR = System.getenv("NO_COLOR") == null && System.console() != null;
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("IDP", "35"), Map.entry("FACADE", "32"), Map.entry("CORE", "33"),
            Map.entry("SFDC", "34"), Map.entry("ASSIST", "36"), Map.entry("SAGA", "36;1"),
            Map.entry("OUTBOX", "33;1"), Map.entry("WEBHOOK", "34;1"), Map.entry("PUSH", "35;1"),
            Map.entry("AUDIT", "90"), Map.entry("CHAOS", "31;1"), Map.entry("APP", "37;1"), Map.entry("LLM", "95"));

    public static void setQuiet(boolean q) { quiet = q; }

    public static void info(String tag, String fmt, Object... args) { print(tag, String.format(fmt, args), false); }

    public static void warn(String tag, String fmt, Object... args) { print(tag, String.format(fmt, args), true); }

    private static synchronized void print(String tag, String msg, boolean warn) {
        if (quiet) return;
        String ts = LocalTime.now().format(T);
        if (COLOR) {
            String c = COLORS.getOrDefault(tag, "37");
            System.out.printf("\u001b[90m%s\u001b[0m \u001b[%sm%-7s\u001b[0m %s%s%s%n", ts, c, tag,
                    warn ? "\u001b[31m" : "", msg, warn ? "\u001b[0m" : "");
        } else {
            System.out.printf("%s %-7s %s%s%n", ts, tag, warn ? "WARN " : "", msg);
        }
    }
}
