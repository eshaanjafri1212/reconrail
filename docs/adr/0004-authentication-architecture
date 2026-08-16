# ADR-0004: Authentication architecture — JWT, signing, lifetime, and enforcement points

- **Status:** accepted
- **Date:** 14 Aug 2026
- **Deciders:** Eshaan Jafri
- **Related:** ADR-0003 (multi-tenancy)

## Context and Problem Statement

ReconRail must authenticate sellers and authorize their actions across a
planned set of ~6 services. The system handles settlement and money-recovery
data, so an authentication flaw is not a cosmetic bug — it exposes one
seller's revenue to another, or allows unauthorized claim submission.

Five decisions have to be made together, because they constrain each other:
the credential mechanism, how tokens are signed, how long they live, where the
client stores them, and where authentication is enforced.

---

## Decision 1: JWT rather than server-side sessions

### Options

**Server-side sessions.** The server stores session state; the client holds an
opaque session ID. Revocation is instant — delete the row. But every
application instance needs access to that store, requiring either sticky
sessions (which break horizontal scaling and complicate rolling deploys) or a
shared session store such as Redis, which becomes a hard runtime dependency
and a single point of failure for authentication.

**JWT (JSON Web Token).** The token itself carries the identity claims and is
signed so it cannot be forged. Any service can verify it independently with no
shared state.

### Decision: JWT

Driven by architecture rather than fashion. ReconRail is a multi-service system
where ingestion-service, recon-engine and workflow-service all need to know who
the caller is. With sessions, every one of those services would need a
connection to the shared session store on every request. With JWTs, each
verifies locally.

### Honest consequence

**A JWT cannot be revoked before it expires.** If a user is disabled or a token
is stolen, that token remains valid until `exp`. This is the real cost of
statelessness and it is mitigated, not eliminated, by Decision 3 (short access
token lifetime plus revocable refresh tokens). Any claim that JWTs are
"more secure" than sessions is wrong; they are differently secure, and the
trade is revocability for scalability.

---

## Decision 2: Asymmetric signing (RS256), not HMAC

### Options

**HMAC / HS256** — one shared secret both signs and verifies. Fast and simple.
But every service that can *verify* a token can also *mint* one. If
recon-engine is compromised, the attacker holds the key to forge a
`PLATFORM_ADMIN` token for any tenant in the system.

**RSA / RS256** — auth-service holds a private key and is the only component
able to issue tokens. Every other service holds only the public key and can
verify but never forge. Signing is slower and there is key-distribution
machinery to build.

### Decision: RS256, with the public key exposed via a JWKS endpoint

The deciding argument is **blast radius**. With HMAC, compromising the least
important service in the system is equivalent to compromising the
authentication system entirely. With RS256, a compromised downstream service
can read tokens it receives but cannot create new ones or escalate privileges.
Given that this system authorizes financial recovery claims, that difference
matters more than the CPU cost of RSA signing.

Choosing this now rather than "later, when there are more services" is
deliberate: retrofitting a signing algorithm across running services means a
coordinated key rotation across every deployment, whereas doing it on day one
costs one afternoon.

### Implementation notes

- Key pair generated once (RSA 2048), private key supplied to auth-service via
  environment variable / mounted secret — never committed.
- `GET /.well-known/jwks.json` publishes the public key in JWKS format so other
  services fetch it rather than having it copied into their configuration.
  Publishing a *public* key is safe by definition.
- The JWT header carries a `kid` (key id) so a future key rotation can run two
  valid keys simultaneously rather than requiring a flag-day cutover.

### Consequence

More moving parts than a shared secret, and services must handle JWKS fetch
failure (cache the key; fail closed on verification, not open).

"Bad": services requiring the caller's email must perform a lookup by user id rather than reading it from the token.
---

## Decision 3: 15-minute access tokens, 30-day rotating refresh tokens

### Reasoning

Token lifetime is a straight trade between exposure window and user friction.
A long-lived access token means a stolen token is useful for a long time; a
short one means constant re-authentication.

The standard resolution is two tokens with different properties:

| | Access token | Refresh token |
|---|---|---|
| Lifetime | 15 minutes | 30 days |
| Storage | stateless (JWT) | **stored server-side, hashed** |
| Purpose | authorize API calls | obtain a new access token |
| Revocable | no | **yes** |
| Sent | on every request | only to `/api/v1/auth/refresh` |

This restores revocation where it counts. Disabling a user invalidates their
refresh token immediately; their access token dies within 15 minutes. That
bounded window is the actual security property being bought, and it is worth
stating precisely rather than claiming tokens are "revoked instantly".

**Rotation with reuse detection:** each refresh returns a *new* refresh token
and invalidates the old one. If a previously-used refresh token is presented
again, that means two parties hold it — the legitimate user and a thief — so
the entire token family for that user is revoked and re-authentication is
forced. This converts refresh-token theft from a silent, indefinite compromise
into a detectable event.

Refresh tokens are stored **hashed** (SHA-256), exactly as passwords are: a
database leak must not hand the attacker usable credentials.

### Why these numbers

15 minutes is short enough that a leaked access token has limited value and
long enough that refreshes are infrequent. 30 days matches the expectation of
sellers who keep a dashboard open daily; a shorter window would log them out
weekly for no security gain given the refresh token is revocable anyway.

---

## Decision 4: `httpOnly`, `Secure`, `SameSite=Strict` cookies for refresh
tokens; access token in memory

### Options

**`localStorage`** — readable by any JavaScript on the page, so a single XSS
vulnerability anywhere (including in a third-party dependency) yields complete
account takeover with a persistent token. Convenient, and the most common
choice in tutorials.

**`httpOnly` cookie** — invisible to JavaScript entirely, so XSS cannot exfiltrate
it. But cookies are attached automatically by the browser, which reintroduces
CSRF risk.

### Decision: split storage

- **Refresh token** → `httpOnly; Secure; SameSite=Strict` cookie, scoped to the
  refresh endpoint path. The long-lived, high-value credential is the one that
  must survive XSS, so it goes where JavaScript cannot reach.
- **Access token** → held in application memory only (a JavaScript variable,
  never persisted). It dies on tab close, and being short-lived limits the
  damage if it is captured.

`SameSite=Strict` means the browser will not attach the cookie to
cross-site requests at all, which addresses CSRF for the cookie-bearing
endpoint. Because the *access* token travels in an `Authorization` header
rather than a cookie, ordinary API requests remain immune to CSRF by
construction, and `csrf().disable()` remains correct for those routes.

### Consequence

Slightly more complex frontend logic (an interceptor that refreshes on 401 and
retries), and the refresh endpoint needs its own CSRF consideration since it is
the one cookie-authenticated route.

---

## Decision 5: Claims — minimal, and never secret

A JWT is **signed, not encrypted**. The payload is base64-encoded and readable
by anyone holding the token. Nothing confidential may go in it.

### Access token claims

```
iss   "reconrail-auth"      issuer
sub   "<user id>"           subject
aud   "reconrail-api"       intended audience
iat   <issued at>
exp   <issued at + 15m>
jti   <uuid>                unique token id, enables future blocklisting
tid   <tenant id>           tenant scope — see ADR-0003
tsl   "<tenant slug>"       tenant slug, for logging and client display
rol   "TENANT_ADMIN"        role — see the staleness note below
```

### Explicitly excluded

Password hashes, any payment or settlement data, full names, phone numbers —
none of it is needed for an authorization decision, and each additional field
is data leaked to anyone who obtains the token.

### Explicitly excluded

Password hashes, any payment or settlement data, phone numbers, and **email
addresses** — none is needed to make an authorization decision, and every
additional claim is data handed to anyone who obtains the token. Email is
excluded deliberately even though it is convenient: a JWT is signed, not
encrypted, so its payload is readable by any party that intercepts or stores
it, and an access token that leaks would otherwise disclose the account's
email address alongside its identifiers. Where a service needs the email, it
resolves it from the user id in the `sub` claim.

### The staleness trade-off

Putting `rol` in the token avoids a database lookup on every request, but means
**a role change does not take effect until the access token expires** — up to
15 minutes. That window is accepted deliberately for ordinary role changes.

Two exceptions where 15 minutes is too long, and the service therefore performs
a live check rather than trusting the claim:

- `PLATFORM_ADMIN` actions — the only role able to cross tenant boundaries,
  and therefore the highest-value target in the system.
- Account disablement — a disabled user must lose access immediately, so the
  `enabled` flag is read from the database rather than carried as a claim.

---

## Enforcement architecture: gateway *and* service

Authentication is enforced twice, deliberately, at different granularities.

**Gateway (Spring Cloud Gateway, arriving later in Phase 1) — coarse-grained.**
Verifies the signature and expiry, applies rate limiting and request size
limits, and rejects invalid requests at the edge so they never reach a service.

**Each service — fine-grained.** Answers questions the gateway structurally
cannot: does settlement #4471 belong to the caller's tenant; may a
`TENANT_USER` approve a recovery claim. Authorization requires domain
knowledge; the gateway has none.

### Why the service re-validates instead of trusting the gateway

This is **zero trust**: assume the internal network may already be
compromised. If auth-service trusted an `X-User-Id` header added by the
gateway, then anything able to reach port 8081 — a firewall misconfiguration, a
compromised sibling service, an SSRF bug, a future pod on the same cluster
network — could impersonate any user in any tenant by setting one header. That
is the **confused deputy** problem: a trusted component tricked into misusing
its authority.

Two rules follow:

1. The gateway **strips** all client-supplied `X-User-*` and `X-Tenant-*`
   headers before forwarding, so a client cannot inject its own identity.
2. Services derive identity **only** from independently verified JWT claims,
   never from a header.

The cost is one signature verification per hop — microseconds, and cheaper
still because the public key is cached.

---

## Consequences

- **Good:** stateless horizontal scaling; a compromised downstream service
  cannot mint tokens; XSS cannot steal the long-lived credential; revocation
  exists where it matters with a bounded 15-minute worst case; identity cannot
  be spoofed by header injection.
- **Bad:** more moving parts than a shared secret and a single long-lived token
  — key management, a JWKS endpoint, refresh rotation logic, and a frontend
  interceptor. Complexity is itself a security risk when it is misunderstood,
  which is why this document exists.
- **Bad:** access tokens remain valid for up to 15 minutes after a role change
  or logout. Accepted, with the two live-check exceptions noted above.
- **Revisit when:** (a) a compliance requirement demands immediate global
  revocation, which would push toward short-lived tokens plus a Redis
  blocklist keyed on `jti`; (b) users need to belong to multiple tenants, which
  changes `tid` from a single claim to an active-tenant selection backed by a
  `user_tenant_role` table; (c) third-party API access is offered, which would
  point toward full OAuth2 with scopes rather than a bespoke token.