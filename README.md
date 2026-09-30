=======
# Meridian Bank: Dispute-Intake Prototype

A runnable slice of the *Meridian Bank Digital AI Assistant* solution architecture. It covers the riskiest integration point in the design: **taking a card dispute from a chat conversation into Salesforce and a legacy SOAP core-banking system without losing or duplicating it**, when those systems are slow, failing, or time out after they have already done the work.

Everything runs locally with **one command**, in either of two ways:

- **Natively** (`./run.sh`): needs a JDK and nothing else.
- **In a hardened, sandboxed container** (`./docker-run.sh`): needs Docker and nothing else, with no JDK on your machine.

Either way there is no Maven or Gradle, no third-party libraries, no API keys, and no internet access at runtime.

**What's in this repository:**

| Deliverable | Where |
|---|---|
| Solution design document | [`design/Meridian_Solution_Design.pdf`](design/Meridian_Solution_Design.pdf); Markdown source in [`design/Meridian_Solution_Design.md`](design/Meridian_Solution_Design.md) |
| Working prototype (this README) | `src/`, `run.sh`, `docker-run.sh` |

---

## 1. Setup (one time)

*Using Docker instead? Skip to [§2b](#2b-run-in-a-sandboxed-container-docker).*

You need a **Java Development Kit (JDK) 17 or newer**. The prototype targets Java 17, and newer JDKs such as 21 also work.

### macOS

1. Install Homebrew if you don't have it (see https://brew.sh):
   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   ```
2. Install the Eclipse Temurin JDK 17:
   ```bash
   brew install --cask temurin@17
   ```
3. Open a **new** terminal and check the install:
   ```bash
   java -version     # should show 17 (or newer)
   javac -version    # should show javac 17 (or newer)
   ```

Without Homebrew, download the macOS `.pkg` installer for Temurin 17 from https://adoptium.net and run it. Then check the install as in step 3.

> **Tip:** macOS ships a `/usr/bin/java` placeholder that says *"Unable to locate a Java Runtime"* until a JDK is installed. `run.sh` detects this and prints the install commands. If you have several JDKs, you can pick one with `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

### Linux / Windows

- **Ubuntu/Debian:** `sudo apt-get install -y openjdk-17-jdk`
- **Windows:** `winget install EclipseAdoptium.Temurin.17.JDK`, then run the commands below from **Git Bash** or **WSL**.

## 2. Run (one command)

```bash
cd meridian_bank_poc
./run.sh
```

Then open **http://localhost:8080**. The script compiles the sources (about 2 seconds) and starts all six services. Press `Ctrl+C` to stop.

| Command | What it does |
|---|---|
| `./run.sh` | Builds, starts everything, and serves the interactive demo console at http://localhost:8080 |
| `./run.sh demo` | Builds and runs a scripted, narrated end-to-end walkthrough in the terminal (~35 s), then exits |
| `./run.sh test` | Builds and runs 45 self-checks (idempotency, retries, security, audit). Exit code 0 means all passed |

If port 8080 is busy, run `MERIDIAN_BASE_PORT=9080 ./run.sh`. The services use the base port through base+5. Set `NO_COLOR=1` to get plain terminal output.

## 2b. Run in a sandboxed container (Docker)

Use this path to run the code isolated from your machine, or on any system without installing Java.

**Setup:** install [Docker Desktop](https://www.docker.com/products/docker-desktop/) (macOS/Windows) or Docker Engine (Linux), and start it once.

```bash
cd meridian_bank_poc
./docker-run.sh          # demo console on http://localhost:8080
./docker-run.sh demo     # scripted walkthrough, run with networking disabled
./docker-run.sh test     # 45 self-checks, run with networking disabled
```

If you prefer Compose, `docker compose up --build` starts the demo console with the same sandbox settings, and `docker compose down` stops it. If port 8080 is busy on your machine, run `MERIDIAN_HOST_PORT=9080 ./docker-run.sh`.

The first build pulls the Eclipse Temurin base images (JDK for compiling, JRE for running). After that, builds take a few seconds.

### What the sandbox enforces

| Control | How | Why it matters |
|---|---|---|
| Minimal runtime image | Multi-stage build: the JDK compiles, and only a JRE plus one read-only jar ship | No compiler or build tools in the running container |
| Unprivileged user | UID/GID 10001, no login shell, no home directory; the jar is root-owned and read-only | The process can't modify its own code or escalate as root |
| Read-only filesystem | `--read-only`, plus a 16 MB `/tmp` tmpfs mounted `noexec,nosuid,nodev`; the JVM runs with `-XX:-UsePerfData` so it writes nothing to disk | Nothing can be persisted or dropped and executed |
| No Linux capabilities | `--cap-drop ALL`, `--security-opt no-new-privileges` | No raw sockets, no mounts, no setuid escalation |
| Bounded resources | 512 MB memory (heap capped at 75 %), 1.5 CPUs, 256 processes | A runaway process can't starve the host |
| Network: `demo` / `test` | `--network none`: loopback only | The code provably needs, and gets, no network access |
| Network: `serve` | Only the assistant port is published, and only on `127.0.0.1` of your machine. The IdP, façade, core-banking and Salesforce mocks bind to the container's own loopback | Nothing is reachable from your LAN, and the "bank-side" services can't be reached even from your host, mirroring the perimeter in the design |
| Health check | `GET /healthz` through bash `/dev/tcp` (no extra tools added to the image) | `docker ps` shows real application health |

These settings live in `docker-run.sh` and `docker-compose.yml`; the image itself also defaults to the unprivileged user. For a production pipeline you would additionally pin base images by digest (`image@sha256:…`), scan the image (e.g. Trivy or Docker Scout), sign it, and apply a seccomp/AppArmor profile. Docker's default seccomp profile is already active.

---

## 3. The demo script (about 90 seconds, live in the browser)

Open http://localhost:8080. The **left** side is the bank's mobile app. The **right** side shows what happens underneath it: chaos controls, the saga, Salesforce, core banking and the audit trail.

| Time | Do this | Say this |
|---|---|---|
| 0:00 | Click **Log in with PIN (acr=low)** | "The app's token was exchanged (RFC 8693) for an assistant-only token. Look at the claims: the `sub` is a pairwise pseudonym, so we never see the customer number. The audience is the assistant only, and the scopes are narrow." |
| 0:08 | Type **"I don't recognise a charge"**, then pick **STREAMFLIX**, **I didn't make this payment**, **Yes** | "Transactions came through the bank's façade, which called the SOAP core and passed on only allow-listed fields. The pending payment can't be picked. The confirmation card is built from system data, not written by the model." |
| 0:20 | Right panel: click **Next write: commit then time out**. Then click **Confirm ×2 (double-tap)** | "I'm making core banking write the dispute and then hang, and I'm tapping confirm twice." |
| 0:24 | Click **Use Face ID** when prompted | "Disputes need a recent strong authentication. The app does a step-up (RFC 9470) and retries with the *same* request id." |
| 0:30 | Point at **Core banking records** and **Salesforce cases** | "The first write timed out, but the façade read before retrying and found it. That's one core record and one case. The second tap was replayed, not re-created. Duplicates: 0." |
| 0:38 | Type **"I was also charged twice"**, then pick **GREENLEAF** and **I was charged twice**. Click **Core banking → Down**, then **Confirm** | "Core goes down mid-dispute. The customer still gets a reference, dated today. The case already exists in Salesforce, and the outbox keeps retrying behind a circuit breaker." |
| 0:50 | Click **Core banking → Normal** | "Core comes back. The outbox registers the dispute using a narrowly-scoped service token, because the customer's token is gone. The chat updates and a push is sent." |
| 0:58 | Tick **deliver status events twice**, click **In Review** on the first dispute | "An agent moves the case in Salesforce. The signed event arrives twice and is applied once. The audit chain is still valid." |
| 1:05 | Click **AI model → All down (guided)**, then type anything | "Both EU model deployments are down, and by design there is no fallback outside the EU. The assistant doesn't guess: it switches to guided mode. Free text is disabled, a banner explains why, and everything runs on buttons with bank-approved wording. You can still dispute a payment end to end." Click **Normal**: after about 5 s the next turn says the assistant is fully available again. **Primary down** shows a silent failover to the secondary EU deployment. |
| 1:15 | *(optional)* Reload the page and log in again, or tap **My disputes** | "A new conversation still shows the customer's existing disputes and their current status, even if they missed a notification. Payments that already have an open dispute are flagged and can't be disputed twice." |

For a hands-free version, run `./run.sh demo`. It plays the same story, plus the security negatives, in the terminal and prints a trace from every service.

---

## 4. What's in the box

```
        ┌──────────── demo console (browser) ────────────┐
        │  mobile-app simulator  │  ops / chaos panel    │
        └───────────┬────────────┴───────────────────────┘
                    │ HTTPS in prod (HTTP on localhost here)
  :8080  ┌──────────▼───────────────────────────┐         :8083 ┌───────────────────────┐
         │ ASSISTANT DATA PLANE                 │ JWT     │ SALESFORCE FSC (mock) │
         │  chat orchestrator + LLM stub        │ bearer  │  OAuth JWT bearer     │
         │  guardrails (PAN, numeric slots)     ├────────►│  Case upsert by       │
         │  dispute saga + outbox + breaker     │ upsert  │  external ID          │
         │  webhook receiver, audit (hashchain) │◄────────┤  status events (HMAC) │
         └──────┬──────────────────┬────────────┘ webhook └───────────────────────┘
   token exch.  │                  │ mTLS* + AT2 / service token
  :8081 ┌───────▼────────┐   :8082 ┌▼───────────────────────┐  :8084 ┌──────────────────┐
        │ MERIDIAN IdP   │         │ ASSISTANT API FAÇADE   │ SOAP   │ CORE BANKING     │
        │ RFC 8693, JWKS │         │ ownership, allow-list, ├───────►│ (non-idempotent, │
        │ private_key_jwt│         │ idempotency, read-     │        │  chaos modes)    │
        └────────────────┘         │ before-write           │        └──────────────────┘
                                   └────────────────────────┘
                 * mTLS is described in the design, not implemented on localhost
  :8085  EU MODEL ENDPOINTS (mock): eu-primary + eu-secondary, reached via the data plane's LLM gateway
```

Each box is a real HTTP server on its own port, so timeouts, status codes, headers and signatures behave as they would in the real integration. Only what sits *behind* each endpoint is simulated.

### Mapping to the solution design

| Design section | Where it lives in the prototype |
|---|---|
| §5 Identity: token exchange, pairwise `sub`, `act` claim, step-up | `idp/MockIdentityProvider`, `assistant/TokenBroker`, `security/TokenValidator`, `ChatService.requireStepUp` |
| §6 Data access: bank-owned façade, field allow-list, ownership | `bank/AssistantFacade` (REST → SOAP), `bank/CoreBankingSimulator` |
| §7 Dispute flow: record → case → core, outbox, five idempotency layers | `assistant/DisputeService`, `AssistantFacade.registerDispute`, `MockSalesforce.upsert` |
| §7 Status updates: events + push + pull-on-open | `MockSalesforce.agentChangesStatus` → `AssistantServer.caseStatusWebhook`; `GET /v1/disputes/{id}`; existing disputes shown when a conversation opens and on **My disputes** (`ChatService.start` / `statusReply`) |
| §9 Errors & retries: RFC 9457, jittered back-off, circuit breaker | `common/Http.Response.problem`, `assistant/RetryPolicy`, `assistant/CircuitBreaker` |
| §10 AI safety: PAN redaction, numbers bound from slots, AI disclosure, audit | `assistant/Guardrails`, `llm/LanguageModelStub`, `AuditLog` |
| §12 Failure modes: core down, Salesforce down, manual review | Chaos controls; `./run.sh test` covers each |
| §12 Model slow/down: failover to the secondary EU endpoint, then **guided mode** | `assistant/LlmGateway` (per-deployment circuit breakers, EU-only failover), `llm/MockModelEndpoint`, `ChatService` (`GUIDED_COPY`, menu, re-probe each turn) |

### The five idempotency layers (and the check that proves each one)

| # | Layer | Proven by |
|---|---|---|
| 1 | The client generates `disputeRequestId` once per confirmation | Demo "Confirm ×2", check *Same disputeRequestId again → same dispute* |
| 2 | The data plane's idempotency store (same key + different payload → **422**) | Check *Same disputeRequestId, different dispute → 422* |
| 3 | Salesforce upsert on external ID `Dispute_Ref__c` | Check *Salesforce recovers → case created* (retried upserts, one case) |
| 4 | Façade **read-before-write** on the client reference (the core is not idempotent) | Check *Timeout after commit → registered once* |
| 5 | One open dispute per transaction | Check *Second dispute on the same transaction → existing dispute returned* |

---

## 5. Decisions made while building the prototype

- **Asynchronous retries use a service token, not the customer's.** The customer's delegated token (5–10 min) will have expired by the time the outbox retries a dispute an hour later. The data plane therefore uses a `client_credentials` token restricted to `facade.disputes.continue`. That token only works together with the confirmed instruction's evidence: the customer's pseudonym, the original token ID, the confirmation time and the `acr`. The façade accepts it only for dispute continuation. The design document should gain one sentence on this.
- **Key reuse with a different payload returns 422, not 409.** This follows the IETF *Idempotency-Key* HTTP header draft: 409 is kept for "already in progress" and conflicting state, such as the one-open-dispute rule at the façade.
- **Zero third-party dependencies.** This keeps the "one command, no external infrastructure" rule honest. The trade-off is a small hand-written JSON codec and JOSE encoding on top of the JDK's own `SHA256withRSA`. That is not custom cryptography, but production would use a vetted library (e.g. Nimbus JOSE+JWT) and HSM/KMS-held keys.
- **Demo timings are shortened.** They are marked in `Config.java`: manual review after 90 s (design: 24 h), async back-off capped at 5 s (design: 15 min), circuit breaker open for 5 s (design: 30 s).

## 6. What is simulated or out of scope (and what production needs)

| Area | Prototype | Production |
|---|---|---|
| Transport security | HTTP on 127.0.0.1 | TLS 1.3 everywhere, mTLS data plane ↔ façade, private connectivity |
| Sender-constrained tokens | Plain bearer | DPoP (app → assistant), mTLS-bound AT2 (RFC 8705) |
| App login | `/demo/app-login` mints the app token directly | Auth Code + PKCE + SCA in the bank app |
| Persistence and outbox | In memory; the "one transaction" is a Java lock | Database transaction (dispute + idempotency + outbox rows), encrypted, bank-held keys |
| Salesforce events | HMAC-signed webhook, at-least-once | Platform Event / CDC via Pub/Sub API (gRPC, replay IDs) |
| Language model | Deterministic keyword stub producing `{{SLOT}}` templates, served over HTTP as two "EU deployments" so failover and guided mode are real | EU-hosted model deployments behind the LLM gateway, plus a classifier for prohibited statements |
| Agent handoff, balances, fee explanations, manual capture when core reads fail | Not in this slice | See design §8 and §12 |
| Keys and secrets | Generated at start-up; webhook secret in code | HSM/KMS, vault, rotation |

## 7. Project layout

```
meridian_bank_poc/
├── design/                        solution design document (PDF + Markdown source + diagram)
├── run.sh                         one-command build + run (serve | demo | test)
├── docker-run.sh                  same, inside a hardened container (no JDK needed)
├── Dockerfile, docker-compose.yml, .dockerignore
├── README.md
└── src/main/
    ├── resources/web/index.html   demo console (mobile-app simulator + ops panel)
    └── java/com/meridian/poc/
        ├── App.java, Platform.java, Config.java
        ├── Demo.java, DemoClient.java, SelfTest.java
        ├── common/     Http (server/client/RFC 9457), Json, Jwt (RS256/JWKS), Soap, Log
        ├── security/   TokenValidator (iss/aud/exp/scope, JWKS cache)
        ├── idp/        MockIdentityProvider (RFC 8693, client_credentials, private_key_jwt)
        ├── bank/       AssistantFacade, CoreBankingSimulator (SOAP), BankDirectory
        ├── salesforce/ MockSalesforce (JWT bearer, upsert, signed status events)
        ├── llm/        MockModelEndpoint (eu-primary / eu-secondary, chaos), LanguageModelStub
        └── assistant/  AssistantServer, ChatService, DisputeService, RetryPolicy,
                        CircuitBreaker, TokenBroker, FacadeClient, SalesforceClient,
                        Guardrails, LlmGateway, AuditLog, Dispute, Conversation
```

## 8. Troubleshooting

- **"A Java Development Kit (JDK) 17 or newer is required":** follow Setup above, then open a new terminal.
- **"Port 8080-8084 is busy":** run `MERIDIAN_BASE_PORT=9080 ./run.sh` and open http://localhost:9080.
- **`permission denied: ./run.sh`:** run `chmod +x run.sh`, or use `bash run.sh`.
- **Docker: "the Docker engine isn't running":** open Docker Desktop and wait until it reports it's running.
- **Docker on Apple Silicon:** the Temurin base images are multi-architecture, so no emulation is needed.
- **To see the service trace while the tests run:** use `VERBOSE=1 ./run.sh test`.

## 9. How AI was used

Claude (Anthropic) was used as a pair-programmer. It drafted the service skeletons, the demo console and this README from my architecture document and my design decisions: saga order, the idempotency layers, the token model, and the failure behaviours. I reviewed and adjusted the code and ran the scripted demo and the self-checks. The self-checks are there to exercise the claims the design makes; they are not a substitute for reading the code. Two refinements came out of building it: the service-token continuation for async retries, and 422 for key reuse (see §5).
