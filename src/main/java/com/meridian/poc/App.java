package com.meridian.poc;

import com.meridian.poc.common.Log;

import java.net.BindException;

/**
 * Entry point.
 * <pre>
 *   ./run.sh          start everything and open the demo console at http://localhost:8080
 *   ./run.sh demo     scripted end-to-end walkthrough in the terminal, then exit
 *   ./run.sh test     self-checks (idempotency, retries, security) with a pass/fail summary
 * </pre>
 * Base port defaults to 8080; override with MERIDIAN_BASE_PORT.
 */
public final class App {
    public static void main(String[] args) throws Exception {
        // Consistent UTF-8 console output on every OS/JDK combination.
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8));
        String mode = args.length > 0 ? args[0] : "serve";
        int base = Integer.parseInt(System.getenv().getOrDefault("MERIDIAN_BASE_PORT", "8080"));
        switch (mode) {
            case "demo" -> System.exit(Demo.run(base));
            case "test" -> System.exit(SelfTest.run(base + 100));
            case "serve" -> serve(base);
            default -> {
                System.err.println("Usage: ./run.sh [serve|demo|test]");
                System.exit(2);
            }
        }
    }

    private static void serve(int base) throws Exception {
        Config cfg = Config.forBasePort(base);
        Platform p;
        try {
            p = new Platform(cfg).start();
        } catch (BindException e) {
            System.err.printf("Port %d-%d is busy. Stop the other process or run: MERIDIAN_BASE_PORT=9080 ./run.sh%n", base, base + 5);
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(p::close));
        System.out.println();
        System.out.println("  Meridian Digital Assistant: dispute-intake prototype");
        System.out.println("  ───────────────────────────────────────────────────");
        System.out.printf("  Demo console      http://localhost:%d%n", cfg.assistantPort);
        System.out.printf("  Assistant API     :%d   (data plane)%n", cfg.assistantPort);
        System.out.printf("  Meridian IdP      :%d   (token exchange, JWKS)%n", cfg.idpPort);
        System.out.printf("  API façade        :%d   (bank DMZ, REST)%n", cfg.facadePort);
        System.out.printf("  Salesforce (mock) :%d%n", cfg.salesforcePort);
        System.out.printf("  Core banking      :%d   (SOAP)%n", cfg.corePort);
        System.out.printf("  EU model (mock)   :%d   (primary + secondary)%n", cfg.modelPort);
        System.out.println("  Ctrl+C to stop.");
        System.out.println();
        Log.info("APP", "all services up");
        Thread.currentThread().join();
    }
}
