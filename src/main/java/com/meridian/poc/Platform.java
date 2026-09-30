package com.meridian.poc;

import com.meridian.poc.assistant.AssistantServer;
import com.meridian.poc.bank.AssistantFacade;
import com.meridian.poc.bank.BankDirectory;
import com.meridian.poc.bank.CoreBankingSimulator;
import com.meridian.poc.common.Jwt;
import com.meridian.poc.idp.MockIdentityProvider;
import com.meridian.poc.llm.MockModelEndpoint;
import com.meridian.poc.salesforce.MockSalesforce;

import java.io.IOException;
import java.security.KeyPair;

/**
 * Wires the six services together. Each listens on its own localhost port so every integration is
 * real HTTP with real timeouts – the only thing "mocked" is what sits behind each endpoint.
 *
 * <pre>
 *   :base+0  assistant data plane (chat API, dispute status, webhook, demo console)
 *   :base+1  Meridian IdP (token exchange, JWKS)          ─┐
 *   :base+2  Assistant API façade (REST)                    ├ "inside Meridian's perimeter"
 *   :base+4  core banking (SOAP)                           ─┘
 *   :base+3  Salesforce FSC (JWT bearer, Case upsert, status events)
 *   :base+5  EU model endpoints (eu-primary / eu-secondary) behind the LLM gateway
 * </pre>
 */
public final class Platform implements AutoCloseable {
    public final Config cfg;
    public final CoreBankingSimulator core;
    public final MockSalesforce salesforce;
    public final AssistantServer assistant;
    private final MockIdentityProvider idp;
    private final AssistantFacade facade;
    public final MockModelEndpoint model;

    public Platform(Config cfg) throws IOException {
        this.cfg = cfg;
        BankDirectory directory = new BankDirectory();

        // Keys the data plane holds (in production: KMS/HSM). Public halves are registered out-of-band.
        KeyPair dataPlaneClientKey = Jwt.newRsaKeyPair();   // private_key_jwt at Meridian's IdP
        KeyPair salesforceCertKey = Jwt.newRsaKeyPair();    // connected-app certificate for JWT bearer flow

        idp = new MockIdentityProvider(cfg, directory);
        idp.registerClientKey(Config.CLIENT_DATAPLANE, dataPlaneClientKey.getPublic());
        core = new CoreBankingSimulator(cfg.corePort);
        facade = new AssistantFacade(cfg, directory);
        salesforce = new MockSalesforce(cfg);
        salesforce.registerConnectedApp(Config.SF_CONSUMER_KEY, salesforceCertKey.getPublic());
        model = new MockModelEndpoint(cfg.modelPort);
        assistant = new AssistantServer(cfg, dataPlaneClientKey, salesforceCertKey);
    }

    public Platform start() {
        idp.start();
        core.start();
        facade.start();
        salesforce.start();
        model.start();
        assistant.start();
        return this;
    }

    @Override
    public void close() {
        assistant.stop();
        salesforce.stop();
        model.stop();
        facade.stop();
        core.stop();
        idp.stop();
    }
}
