# ReconRail

> Settlement reconciliation and money-recovery engine for Indian multi-channel
> sellers. It doesn't just find where your money leaked — it gets it back.

**Detect → Explain → Recover.**

**Live:** https://reconrail.in/actuator/health

🚧 In active development. Started Aug 2026.

## The problem

Small Indian sellers on Amazon, Flipkart, Meesho, quick-commerce, and ONDC
receive 5–8 settlement reports in incompatible formats. An estimated 2–4% of
revenue leaks monthly through fee mismatches, missed reimbursements, and
return black holes. Existing tools detect and stop at a report; ReconRail
automates the recovery claim itself.

## Architecture

<diagram coming in Phase 1>

Stack: Java 21 · Spring Boot 4 · Spring Security · PostgreSQL · Flyway ·
Docker · GitHub Actions · Nginx · Cloudflare · OCI (ARM)
Planned: Spring Cloud Gateway · Kafka · Redis · Flowable · Angular

## Local setup

```bash
git clone https://github.com/eshaanjafri1212/reconrail.git
cd reconrail
bash scripts/generate-dev-keys.sh        # RSA keys for JWT signing (gitignored)
cd infra && docker compose up -d postgres
cd ../services/auth-service && mvn spring-boot:run
```

Service starts on `http://localhost:8081`. Health: `/actuator/health`.

## Roadmap

| Phase | Scope | Status |
|-------|-------|--------|
| 0 | Spec, repo, cloud provisioning, ADRs | ✅ Complete (03 Aug 2026) |
| 1 | Auth service + gateway + CI/CD + live deploy | 🟡 In progress |
| 2 | Ingestion + Kafka event backbone | ⬜ |
| 3 | Reconciliation engine | ⬜ |
| 4 | Workflows + Auto-Claim (the USP) | ⬜ |
| 5 | Real-time dashboard + webhooks + resilience | ⬜ |
| 6 | Observability, load tests, production polish | ⬜ |

### Phase 1 progress

- ✅ Multi-tenant registration and login (BCrypt, tenant-scoped users)
- ✅ RS256 JWT issuance and verification, refresh tokens with rotation
- ✅ Spring Security filter chain, stateless authentication
- ✅ CI/CD: build, test, multi-arch image → ghcr.io → automated deploy
- ✅ Live behind Nginx + Cloudflare (Full strict TLS)
- ⬜ Tenant context enforcement (Hibernate filters + RLS)
- ⬜ Spring Cloud Gateway with rate limiting
- ⬜ Integration tests including tenant isolation tests

## Engineering practices

Spec-driven development ([docs/SPEC.md](docs/SPEC.md)) · Architecture Decision
Records ([docs/adr/](docs/adr/)) · Runbooks ([docs/runbooks/](docs/runbooks/)) ·
Conventional commits · PR-only main with required CI checks · Flyway migrations ·
Build-once-deploy-many · Fail-closed secret handling