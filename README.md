# ReconRail
**Live:** https://reconrail.in/actuator/health
> Settlement reconciliation and money-recovery engine for Indian multi-channel
> sellers. It doesn't just find where your money leaked — it gets it back.

**Detect → Explain → Recover.**

🚧 In active development. Started Aug 2026. [Live demo — coming Phase 1]

## The problem

Small Indian sellers on Amazon, Flipkart, Meesho, quick-commerce, and ONDC
receive 5–8 settlement reports in incompatible formats. An estimated 2–4% of
revenue leaks monthly through fee mismatches, missed reimbursements, and
return black holes. Existing tools detect and stop at a report; ReconRail
automates the recovery claim itself.~~~~

## Architecture

<diagram coming in Phase 1>

Planned stack: Java 21 · Spring Boot · Spring Cloud Gateway · Kafka · Redis ·
PostgreSQL · Flowable · Angular · Docker · GitHub Actions · OCI (ARM)

## Roadmap

| Phase | Scope                                        | Status                    |
|-------|----------------------------------------------|---------------------------|
| 0     | Spec, repo, cloud provisioning, ADRs         | 🟡 Complete (03 Aug 2026) |
| 1     | Auth service + gateway + CI/CD + live deploy | In Progress               |
| 2     | Ingestion + Kafka event backbone             | ⬜                         |
| 3     | Reconciliation engine                        | ⬜                         |
| 4     | Workflows + Auto-Claim (the USP)             | ⬜                         |
| 5     | Real-time dashboard + webhooks + resilience  | ⬜                         |
| 6     | Observability, load tests, production polish | ⬜                         |

## Engineering practices

Spec-driven development ([docs/SPEC.md](docs/SPEC.md)) · Architecture Decision
Records ([docs/adr/](docs/adr/)) · Conventional commits · PR-only main ·
Flyway migrations · Structured logging · Testcontainers