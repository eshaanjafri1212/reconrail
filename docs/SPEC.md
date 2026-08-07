# ReconRail — Master Specification & Journey Document
**Version:** 0.1.2 (Phase 0 — Foundation Draft)
**Owner:** Eshaan Abbas Jafri (solo builder, nights & weekends)
**Status:** Living document — updated at the end of every phase
**Last updated:** 07 Aug 2026

---

## 1. CONTEXT — What is this and why are we here?

### 1.1 One-line pitch
**ReconRail is a settlement reconciliation and money-recovery engine for small multi-channel Indian online sellers — it doesn't just find where your money leaked, it gets it back.**

### 1.2 The problem
A small Indian seller selling on Amazon + Flipkart + Meesho + Shopify + quick-commerce (Blinkit/Zepto) + ONDC receives 5–8 different settlement reports, each with its own format, cycle, and deduction logic (commissions, shipping/weight-handling fees, TCS/GST, returns, RTO penalties). Industry sources estimate 2–4% of revenue silently leaks every month through mismatches, missed reimbursement claims, and return black holes. Manual Excel reconciliation takes 10–15 hours/week and most sellers simply give up and absorb the loss.

### 1.3 Why existing solutions fall short
- **Enterprise OMS suites (Unicommerce):** reconciliation is a bundled afterthought, priced and designed for large brands.
- **Payment-gateway recon (Cashfree, Stripe/Recko):** reconciles PG settlements, not marketplace commissions/returns/TCS.
- **Small bootstrapped tools (eVanik, SaySeller, etc.):** detect discrepancies but **stop at a report**. The seller still has to fight the marketplace manually. No ONDC or quick-commerce coverage. No real-time visibility.

### 1.4 The USP (this is what we sell)
> **"Detect → Explain → Recover."**
1. **Detect** — event-driven matching engine reconciles every order across every channel.
2. **Explain** — each discrepancy is classified with a plain-language reason ("Flipkart charged weight-handling for 2kg; your listed weight is 0.8kg") — not just a red row in a table.
3. **Recover** — the **Auto-Claim Engine** (Flowable workflow) drafts the reimbursement claim, tracks its SLA, escalates, and records recovered money. A live **Money Leak Meter** (WebSocket) shows leakage vs. recovery in real time.

**Business-model USP:** freemium detection; monetize recovery (flat plan ₹499–₹1,500/mo OR success fee % of recovered amount). The product literally pays for itself — that is the sales pitch.

### 1.5 Dual purpose of this project
- **Portfolio flagship:** deployed 24/7, publicly accessible, demonstrating production-grade backend engineering (target: ₹12–30 LPA product/GCC roles).
- **Startup MVP:** if validation succeeds (≥3 active sellers, ≥1 paying by Month 6), this becomes the venture.

---

## 2. REQUIREMENTS

### 2.1 Functional Requirements (FR)
| ID | Requirement | Priority |
|----|-------------|----------|
| FR-01 | Multi-tenant signup/login; each seller = isolated tenant | P0 |
| FR-02 | Upload settlement files (CSV/XLSX) for Amazon, Flipkart, Meesho (launch set) | P0 |
| FR-03 | Parse + normalize files into a canonical `SettlementEvent` schema (versioned) | P0 |
| FR-04 | Upload/sync order data (order file upload first; API connectors later) | P0 |
| FR-05 | Reconciliation engine: match orders ↔ payouts ↔ fees ↔ returns; classify discrepancies (SHORT_PAID, OVERCHARGED_FEE, MISSING_RETURN_REFUND, UNRECONCILED, OK) | P0 |
| FR-06 | Discrepancy explanation: human-readable reason per mismatch | P0 |
| FR-07 | Exception workflow (Flowable): human review queue, approve/dispute, SLA timers, escalation | P0 |
| FR-08 | Auto-Claim Engine: generate claim drafts (templated per marketplace), track claim lifecycle (DRAFT → FILED → ACCEPTED/REJECTED → RECOVERED) | P1 |
| FR-09 | Real-time dashboard: Money Leak Meter via WebSocket (live leakage, recovery, reconciliation progress) | P1 |
| FR-10 | Webhooks OUT: notify seller systems on events (reconciliation complete, claim recovered) with HMAC signatures + retries | P1 |
| FR-11 | Webhooks IN: receive marketplace/payment events where available (Shopify webhooks first) | P2 |
| FR-12 | Reports: monthly leakage report, fee-audit report, recovery report (PDF/CSV export) | P1 |
| FR-13 | Notifications: email (launch), WhatsApp (later) | P1 |
| FR-14 | Admin panel: tenant management, parser health, dead-letter queue viewer | P2 |
| FR-15 | ONDC + quick-commerce settlement formats | P2 (differentiator, post-MVP) |

### 2.2 Non-Functional Requirements (NFR)
| ID | Requirement |
|----|-------------|
| NFR-01 | **Availability:** public demo up 24/7; graceful degradation if Kafka down (queue to outbox) |
| NFR-02 | **Performance:** reconcile 100k rows in < 2 min; API p95 < 300ms (publish real numbers in README) |
| NFR-03 | **Security:** JWT/OAuth2, tenant isolation at query level, encrypted secrets, OWASP top-10 checklist, rate limiting at gateway |
| NFR-04 | **Idempotency:** file re-uploads and event re-delivery must never double-count (idempotency keys everywhere) |
| NFR-05 | **Auditability:** immutable ledger of all money events; every state change traceable |
| NFR-06 | **Testing:** >80% coverage on matching engine; integration tests with Testcontainers; contract tests for parsers |
| NFR-07 | **Observability:** structured logs, Prometheus metrics, Grafana dashboard, health endpoints, correlation IDs |
| NFR-08 | **Cost:** must run inside Oracle Always Free (2 OCPU / 12 GB ARM) at launch |
| NFR-09 | **Schema evolution:** versioned parsers + event schemas; a marketplace format change must not break old data |

---

## 3. ARCHITECTURE

### 3.1 Services (lean — fits 12 GB RAM)
1. **edge:** Nginx (TLS, static, reverse proxy) → Spring Cloud Gateway (routing, rate limit, JWT validation)
2. **auth-service:** Spring Security, JWT/OAuth2, multi-tenancy, user/tenant mgmt — PostgreSQL
3. **ingestion-service:** file upload, parser registry (strategy pattern, versioned parsers), publishes normalized events → Kafka
4. **recon-engine:** Kafka consumer; matching rules; Redis-cached lookups; writes immutable ledger — PostgreSQL
5. **workflow-service:** Flowable engine; exception queues; Auto-Claim lifecycle; SLA timers
6. **realtime-notify-service:** Kafka consumer → WebSocket push (Money Leak Meter) + email + outbound webhooks (HMAC, retry with backoff, dead-letter)
7. **frontend:** Angular SPA (hosted free on Vercel/Render static)

*(Reporting lives inside recon-engine initially; split later. Service registry: start with Docker DNS + Gateway routes; introduce Eureka/Consul in Phase 5 as a learning exercise, documented honestly as such.)*

### 3.2 Key patterns & concepts we will implement (the curriculum)
| Concept | Where it's used |
|---------|----------------|
| Event-driven architecture, Kafka topics/partitions/consumer groups | ingestion → recon pipeline |
| Transactional Outbox pattern | reliable event publish from recon-engine |
| Idempotent consumers, exactly-once semantics (practical) | recon-engine |
| Saga (choreography) | claim lifecycle across services |
| Strategy + Factory patterns | marketplace parser registry |
| State machine / workflow engine | Flowable claim & exception flows |
| CQRS-lite (read models) | dashboard aggregates |
| Caching (cache-aside, TTL, invalidation) | Redis in recon-engine |
| WebSockets (STOMP over Spring) | Money Leak Meter |
| Webhooks (in + out, HMAC signing, retries, DLQ) | notify-service, Shopify inbound |
| API Gateway, rate limiting, circuit breaker (Resilience4j) | edge + inter-service calls |
| Service registry & discovery (Eureka) | Phase 5 learning module |
| Load balancing (Nginx upstream + client-side via Spring Cloud LoadBalancer) | Phase 5 |
| Multi-tenancy (shared schema, tenant_id discriminator + row-level checks) | all services |
| Database: indexing, partitioning ledger by month, query plans | PostgreSQL |
| Zero-downtime deploys (blue-green via Nginx), DB migrations (Flyway) | CI/CD |
| Observability: Prometheus, Grafana, structured logging, correlation IDs | all |
| Load testing (k6/Gatling) + publishing p95 numbers | Phase 6 |

### 3.3 Deployment topology
- **Compute:** Oracle Always Free ARM VM (2 OCPU / 12 GB) — Docker Compose; JVM heaps capped (~256m/service); single-broker Kafka **or Redpanda** (lighter on ARM — decide in Phase 1 via a short ADR)
- **Frontend:** Vercel/Render free static
- **DNS/TLS:** Cloudflare free + custom domain (~₹800/yr — the only spend)
- **CI/CD:** GitHub Actions → build, test, multi-arch image → SSH deploy to VM
- **Azure keyword play:** separate 1-day micro-deployment (App Service free) documented in README
- **Scale path (later, if startup path chosen):** VM → Hetzner/DO (₹400–800/mo) → managed K8s + managed Postgres when >50 tenants

---

## 4. PHASE PLAN (the journey)

| Phase | Duration | Outcome | Key learnings |
|-------|----------|---------|---------------|
| **0. Spec & Setup** | Week 1 | This document; GitHub repo/org; Oracle account provisioned; domain bought; ADR-001 (monorepo), ADR-002 (Kafka vs Redpanda) | ADRs, spec-driven development |
| **1. Foundation** | Weeks 2–5 | auth-service + gateway + Docker Compose + CI pipeline + deployed "secured skeleton" with live URL | Spring Security deep-dive, JWT/OAuth2, multi-tenancy, Flyway, GitHub Actions |
| **2. Ingestion & Event Backbone** | Weeks 6–9 | File upload, 3 parsers, canonical schema, Kafka topics, idempotent publish | Kafka fundamentals, schema versioning, strategy pattern, Testcontainers |
| **3. Recon Engine** | Weeks 10–14 | Matching rules, discrepancy classification + explanations, Redis caching, immutable ledger, first end-to-end report | Outbox, idempotent consumers, cache-aside, DB indexing/partitioning, heavy JUnit/Mockito |
| **4. Workflows & Recovery (USP)** | Weeks 15–18 | Flowable exception queues, Auto-Claim engine, saga lifecycle | Workflow engines, state machines, saga pattern |
| **5. Real-time & Integration Layer** | Weeks 19–22 | WebSocket Money Leak Meter, outbound webhooks (HMAC/retry/DLQ), Shopify inbound webhook, Eureka + load-balancing module, circuit breakers | WebSockets/STOMP, webhook design, service discovery, Resilience4j |
| **6. Production Polish** | Weeks 23–26 | Angular dashboard polish, Prometheus/Grafana, load test + published p95, security hardening, docs, Loom walkthrough, landing page | Observability, load testing, zero-downtime deploys |
| **7. Validation & Launch** | Ongoing | 10 seller interviews → 3 pilot users → decide startup path | Customer discovery, GTM |

### Phase 7 startup playbook (if we go that way)
1. **Discovery first (can start NOW, parallel):** interview 10 small sellers (Facebook/Telegram seller groups, r/IndianEcommerce, local Bengaluru sellers). Script: "How do you check if Amazon paid you correctly?" Listen for hours-lost and money-lost numbers.
2. **Pilot:** onboard 3–5 sellers free; personally run their reconciliation; log every recovered rupee — those numbers become the landing page.
3. **Launch surface:** landing page + Loom demo → LinkedIn build-in-public posts (feeds your content calendar Pillar 1) → Product Hunt / seller communities.
4. **Pricing test:** ₹499/mo vs 10% of recovered — let pilots choose, learn which converts.
5. **Later:** register entity only after first paying customer; explore ONDC ecosystem partnerships; raise only if >₹1L MRR trajectory (2026 funding climate rewards revenue).

---

## 5. WORKING AGREEMENT (how Eshaan + Claude build this)
- Every phase starts by re-reading this spec; every phase ends by updating it (version bump + changelog).
- Every significant decision → an ADR file in `/docs/adr/`.
- Claude explains the *why* (concept, pattern, trade-offs, interview-relevant framing) before the *how* (code).
- Eshaan writes/owns the code; Claude reviews, teaches, and pair-designs. Goal: Eshaan can defend every line in a 30-minute interview grilling.
- Git discipline: feature branches, PRs (even solo), conventional commits, small daily commits.
- DSA prep runs in parallel in a separate track; LinkedIn build-in-public posts sourced from each phase's learnings.

## 6. CHANGELOG
- **v0.1 (31 Jul 2026):** Initial spec created. Phase 0 begun.
- v0.1.1 (02 Aug 2026): Confirmed OCI Always Free reduced to 2 OCPU / 12 GB (June 2026 change). Architecture unchanged — NFR-08 already assumed this.
- **v0.1.2 (03 Aug 2026):** Phase 0 complete — repo + scaffold, OCI VM
  provisioned (Mumbai, A1.Flex 2/12, Docker installed), reconrail.in live
  behind Cloudflare (proxied, TLS Full). Provisioning runbook added at
  docs/runbooks/server-provisioning.md. ADR-002 (Kafka vs Redpanda) remains
  proposed, due Phase 2.