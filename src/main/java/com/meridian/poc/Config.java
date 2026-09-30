package com.meridian.poc;

import java.time.Duration;

/**
 * Ports and timing budgets. Production values from the design are noted alongside;
 * a few are shortened so the demo shows retries, back-off and manual review within seconds.
 */
public final class Config {
    public final int assistantPort, idpPort, facadePort, salesforcePort, corePort, modelPort;

    /**
     * Interface the assistant data plane listens on. Loopback by default; the container image sets 0.0.0.0 so
     * the demo console can be published. The bank-side and Salesforce mocks always stay on loopback, so inside
     * a container they are unreachable from the host – mirroring the bank perimeter in the design.
     */
    public final String assistantBindAddress = System.getenv().getOrDefault("MERIDIAN_BIND_ADDRESS", "127.0.0.1");

    // Data plane -> façade (design §9: reads 1.5 s, writes 5 s)
    public final Duration facadeReadTimeout = Duration.ofMillis(1500);
    public final Duration facadeWriteTimeout = Duration.ofMillis(3000);
    // Façade -> core SOAP. Shorter than the caller's timeout so the façade can answer 504 itself.
    public final Duration coreCallTimeout = Duration.ofMillis(1200);
    // Data plane -> Salesforce
    public final Duration salesforceTimeout = Duration.ofMillis(2000);
    // Customer-facing budget for the synchronous part of the dispute saga (design: 8 s)
    public final Duration userBudget = Duration.ofSeconds(8);
    // Outbox worker tick and async back-off cap (design: cap 15 min; demo: 5 s)
    public final Duration outboxTick = Duration.ofMillis(500);
    public final Duration asyncBackoffCap = Duration.ofSeconds(5);
    // Retries exhausted -> MANUAL_REVIEW (design: 24 h; demo: 90 s)
    public final Duration manualReviewAfter;
    // Step-up: dispute submission needs acr=high with auth_time no older than this (design: 5 min)
    public final Duration stepUpMaxAge = Duration.ofMinutes(5);
    // Circuit breaker (design: open at 50 % failures over 20 calls, half-open after 30 s; demo: 5 s)
    public final int breakerWindow = 20, breakerMinCalls = 5;
    public final double breakerFailureRate = 0.5;
    public final Duration breakerOpenFor = Duration.ofSeconds(5);

    // Data plane -> EU model endpoints (LLM gateway). Breaker per deployment (demo: re-probe after 5 s).
    public final Duration llmTimeout = Duration.ofMillis(1200);
    public final Duration llmBreakerOpenFor = Duration.ofSeconds(5);

    // Shared secret for Salesforce -> assistant webhook signatures (would live in a vault)
    public final String webhookSecret = "whsec_demo_9f2c1e7a5b";

    private Config(int base, Duration manualReviewAfter) {
        this.assistantPort = base;
        this.idpPort = base + 1;
        this.facadePort = base + 2;
        this.salesforcePort = base + 3;
        this.corePort = base + 4;
        this.modelPort = base + 5;
        this.manualReviewAfter = manualReviewAfter;
    }

    public static Config forBasePort(int base) { return new Config(base, Duration.ofSeconds(90)); }

    public static Config forBasePort(int base, Duration manualReviewAfter) { return new Config(base, manualReviewAfter); }

    public String assistantUrl() { return "http://127.0.0.1:" + assistantPort; }
    public String idpUrl() { return "http://127.0.0.1:" + idpPort; }
    public String facadeUrl() { return "http://127.0.0.1:" + facadePort; }
    public String salesforceUrl() { return "http://127.0.0.1:" + salesforcePort; }
    public String coreUrl() { return "http://127.0.0.1:" + corePort; }
    public String modelUrl() { return "http://127.0.0.1:" + modelPort; }

    public String idpIssuer() { return idpUrl(); }
    public String idpTokenEndpoint() { return idpUrl() + "/oauth2/token"; }
    public String idpJwks() { return idpUrl() + "/.well-known/jwks.json"; }

    // OAuth audiences and client ids (design §5)
    public static final String AUD_MOBILE = "meridian-mobile-api";
    public static final String AUD_ASSISTANT = "meridian-assistant";
    public static final String AUD_FACADE = "meridian-assistant-facade";
    public static final String CLIENT_MOBILE = "meridian-mobile-app";
    public static final String CLIENT_DATAPLANE = "assistant-dataplane";
    public static final String SF_CONSUMER_KEY = "3MVG9.assistant-integration";
    public static final String SF_INTEGRATION_USER = "assistant.integration@meridian.example";
}
