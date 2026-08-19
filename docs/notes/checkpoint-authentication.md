# Checkpoint: Authentication (Phase 1)

**Completed:** 19 Aug 2026
**Covers:** registration, login, JWT issuance and verification, refresh tokens,
the security filter chain, and production key management.
**Related:** ADR-0003 (multi-tenancy), ADR-0004 (authentication architecture).

---

## Part 1 — What exists now, and how the pieces fit

### The registration flow

`POST /api/v1/auth/register` creates a tenant (a seller workspace) and its first
admin user in a single transaction.

- The company name is converted to a **slug** (`Kirana Traders` →
  `kirana-traders`). The slug is a public, human-readable identifier used at
  login and in URLs. We never expose sequential database ids externally: an id
  in a URL tells anyone how many customers you have and invites enumeration
  (trying 1, 2, 3… to see what leaks) — the OWASP issue known as
  *insecure direct object reference*.
- Passwords are hashed with **BCrypt** (work factor 10) — never stored, never
  reversible.
- Email is unique **per tenant**, not globally, because the same person may
  legitimately work for two sellers.
- Creating the tenant and the user is `@Transactional`: if either fails, both
  roll back. Otherwise a crash between the two writes leaves an orphaned tenant
  nobody can log into.

### The login flow

`POST /api/v1/auth/login` takes `(tenantSlug, email, password)` and returns two
tokens with very different properties:

| | Access token | Refresh token |
|---|---|---|
| Lives | 15 minutes | 30 days |
| Format | signed JWT, stateless | opaque random string |
| Stored server-side? | no | yes, **as a SHA-256 hash** |
| Travels in | JSON body → `Authorization` header | `httpOnly` cookie |
| Revocable | no | yes |

The split exists because statelessness and revocability are in tension. A JWT
can be verified by any service without a database lookup, which is what makes
horizontal scaling easy — but it also means **it cannot be cancelled before it
expires**. Keeping the access token short (15 min) bounds that exposure, while
the refresh token is stored server-side so it *can* be revoked instantly.

### Why the refresh token lives in a cookie

A cookie is data the browser stores and sends back automatically. Setting
`httpOnly` makes it **invisible to JavaScript**, so an XSS bug anywhere on the
page — including inside a third-party library — cannot read it. If we returned
the refresh token in the JSON body instead, the frontend would have to keep it
in `localStorage`, where any script on the page can read it, and a single XSS
would hand an attacker 30 days of access.

The other cookie attributes:

- **`Secure`** — only sent over HTTPS, so it can't be captured on an untrusted
  network.
- **`SameSite=Strict`** — the browser refuses to attach the cookie to requests
  originating from another site. This is the **CSRF** defence: without it, a
  malicious page could POST to our API and the browser would helpfully attach
  the cookie, making the forged request look authenticated.
- **`Path=/api/v1/auth`** — the cookie is only sent to auth endpoints, not on
  every API call. Fewer places it travels, fewer places it can leak.

### Refresh token families (rotation and theft detection)

Every login starts a new **family** (a UUID shared by that login's whole token
chain). Each refresh issues a *new* token and marks the old one replaced. If a
token that has already been used is presented again, two parties hold it — the
legitimate user and a thief — so the **entire family is revoked** and everyone
must log in again. This turns silent, indefinite refresh-token theft into a
detectable event.

### The security filter chain

Spring Security is a chain of filters that runs *before* controllers. Identity
lives in one place: `SecurityContextHolder`, a ThreadLocal holding the current
request's `Authentication`.

Our `JwtAuthenticationFilter`:

1. Reads the `Authorization: Bearer <token>` header.
2. If absent or malformed → **does nothing and continues the chain**.
3. If present → verifies the signature, expiry, issuer and audience.
4. On success → builds an `Authentication` and puts it in the context.
5. On failure → clears the context and continues.

**The filter never rejects a request itself.** That is deliberate: it
establishes *identity*, and the authorization layer decides *access*. If the
filter threw on a missing header, `/login` and `/register` — which are public —
would break. The rule `anyRequest().authenticated()` handles rejection.

It extends `OncePerRequestFilter` because a plain servlet filter can run several
times per request (forwards, includes, async dispatches), and verifying a token
three times is both wasteful and unpredictable.

Finally, `JwtAuthenticationEntryPoint` converts unauthenticated access into a
**401** with an RFC 7807 problem-detail body. Without it Spring returns **403**,
which is misleading: 401 means "I don't know who you are — authenticate", while
403 means "I know who you are and you may not do this." A frontend seeing 401
knows to refresh its token; 403 suggests refreshing won't help.

### Security properties deliberately built in

- **No user enumeration.** Unknown tenant, unknown email, wrong password and
  disabled account all return an identical 401 "Invalid credentials". Any
  difference would let an attacker discover which accounts exist.
- **No timing leak.** When the user isn't found we still run a BCrypt
  comparison against a dummy hash, so a missing account takes the same ~100ms
  as a wrong password. Returning early would leak the same information the
  error message carefully hides.
- **`InvalidCredentialsException` has no message-taking constructor**, so it is
  structurally impossible for a future change to leak a helpful-but-dangerous
  detail.
- **Minimal JWT claims.** A JWT is *signed, not encrypted* — the payload is
  readable by anyone holding the token. Email was deliberately removed: it
  isn't needed for an authorization decision, and every claim is data disclosed
  to whoever obtains the token.
- **Algorithm fixed in code**, never read from the token, closing the classic
  `alg: none` and RS256→HS256 confusion attacks.

---

## Part 2 — Problems we hit, and how we solved them

Written plainly, because the debugging method matters more than the fixes.

### 1. CI went red: "Invalid RSA private key at classpath:keys/private.pem"

**What happened.** Signing keys are gitignored, so they exist only on the
laptop. The CI runner clones a repo with no keys, the app fails to start, and
the context-load test fails.

**Why it happened.** This is the system working as designed. `TokenServiceImpl`
throws on unreadable keys, which means an auth service that cannot verify
anything refuses to start rather than running in a broken state — **fail
closed**.

**How we fixed it.** Not by committing keys. We added
`scripts/generate-dev-keys.sh` and a CI step that runs it, so each build creates
throwaway keys that die with the runner.

**The lesson.** *The first time a service fails closed on a missing secret,
every environment that lacks that secret breaks. The fix is to provision the
secret properly per environment — never to relax the check.*

### 2. Production deploy timed out (exit code 124)

**What happened.** The deploy job pulled the image, started the containers, then
hung and failed after 120 seconds.

**How we diagnosed it.** Exit 124 is the `timeout` command reporting its
command never finished — meaning our readiness-polling loop never saw UP.
Container logs showed the same missing-key error, now in production.

**The fix.** Generate a **separate production key pair** on the VM and mount it
into the container read-only, pointing `JWT_PRIVATE_KEY` at the mounted path.
Development keys are never promoted to production.

**Worth noting:** the pipeline behaved correctly. It refused to report success
for a deployment that never became healthy. A pipeline that merely runs commands
and declares victory would have left a dead service behind a green checkmark.

### 3. "FileNotFoundException: run/secrets/keys/private.pem"

**What happened.** After mounting the keys, the app still couldn't find them.

**How we diagnosed it.** Read the exception chain bottom-up. The deepest cause
prints the *literal path attempted* — and it was missing its leading slash, so
it was being resolved relative to the working directory (`/app/run/secrets/…`)
instead of the absolute path.

**The fix.** `file:/run/secrets/...`, not `file:run/secrets/...`. One character.

**The lesson.** *Read exception chains from the bottom. The last "Caused by" is
the actual fact; everything above it is context.*

### 4. "Permission denied" on the key file

**What happened.** Path fixed, but the container still couldn't read the key.

**Why.** Two correct security decisions collided. The Dockerfile runs the app as
an **unprivileged user** rather than root (so a container escape doesn't hand
over the host). On the VM, the private key was `chmod 600` — readable only by
its owner, `ubuntu`. Inside the container the app user has a different UID, is
therefore not the owner, and `600` grants nobody else access.

**The fix, and the better fix.** Short term, change the file's owner to the
container's UID. Properly: **pin the UID in the Dockerfile**
(`adduser -u 10001`) so the contract between image and host is explicit and
stable across rebuilds, rather than discovered by trial and error.

**The lesson.** *Defence in depth has costs. The answer is never to weaken a
control — it is to make the interaction between controls explicit.*

### 5. 403 where 401 belonged

Unauthenticated requests returned 403. Spring treats a request with no
authentication as *anonymous*, and an anonymous user lacking permission is a
403. Semantically wrong for an API: the client needs to know to authenticate.
     Fixed with a custom `AuthenticationEntryPoint` returning 401.

### 6. Earlier in the block

- **Registration returned 403 instead of 409.** An uncaught exception caused an
  internal forward to `/error`, which the security rules blocked. Fixed by
  permitting `/error` and ensuring the `@RestControllerAdvice` was picked up by
  component scanning.
- **`Found more than one migration with version 001`.** Both
  `V1__create_users_table.sql` and `V001__create_users_table.sql` existed after
  a rename. Flyway refuses to guess which is authoritative.
- **Local login authenticated against the wrong database.** A Windows-installed
  PostgreSQL was occupying port 5432, so the app never reached the Docker
  container. Found via `netstat` then `Get-Process`.

---

## Part 3 — Interview questions from this block

### "Walk me through what happens when a user logs in."

> The request carries a tenant slug, email and password. I look up the tenant,
> then the user scoped to that tenant, and verify the password with BCrypt. On
> success I issue two things: a 15-minute access token, which is an RS256-signed
> JWT carrying the user id, tenant id and role, and a 30-day refresh token,
> which is 256 bits of random data. The refresh token is stored in the database
> as a SHA-256 hash and returned to the browser in an httpOnly, Secure,
> SameSite=Strict cookie so JavaScript can never read it. The access token goes
> in the response body and the client sends it as a Bearer header. On subsequent
> requests a filter verifies the token's signature, expiry, issuer and audience,
> and populates the security context with the caller's identity.

### "Why two tokens rather than one?"

> Because statelessness and revocability pull in opposite directions. A JWT can
> be verified locally by any service with no shared session store, which is what
> makes horizontal scaling straightforward — but it also can't be cancelled
> before it expires. A single long-lived JWT would mean a stolen token stays
> valid for its whole lifetime. So the access token is short-lived to bound that
> window, and the refresh token is stored server-side so it can be revoked
> immediately. Disabling a user kills the refresh token instantly and the access
> token dies within fifteen minutes.

### "Your login fails. What do you return?"

> The same 401 and the same message for every failure — unknown tenant, unknown
> email, wrong password, disabled account. If they differed, an attacker could
> submit a junk password against an email and learn from the error whether that
> account exists, which is the first step in credential stuffing or a targeted
> phishing campaign. I also defend the timing channel: when the user isn't
> found I still run a BCrypt comparison against a dummy hash, because returning
> in two milliseconds instead of a hundred leaks exactly the same information
> the error message is careful not to.

### "Is a JWT encrypted?"

> No, it's signed. The payload is base64 and readable by anyone holding the
> token — signing proves integrity and authenticity, not confidentiality.
> That's why my claim set is minimal and why I removed the user's email from it:
> it wasn't needed to make an authorization decision, and every claim is data
> disclosed to whoever obtains the token.

### "Why RS256 rather than HS256?"

> Blast radius. With HMAC, every service that can verify a token can also mint
> one, so compromising the least important service in the system is equivalent
> to compromising authentication entirely — an attacker could forge a platform
> admin token for any tenant. With RS256 only the auth service holds the private
> key; everything else holds the public key and can verify but never forge.
> Given this system authorizes financial recovery claims, that difference is
> worth the extra key-distribution machinery.

### "Explain your filter. Why doesn't it reject bad tokens?"

> Its job is to establish identity, not to decide access. If the header is
> missing or the token is invalid it leaves the security context empty and lets
> the chain continue — the authorization rules then reject the request if the
> endpoint requires authentication. If the filter threw on a missing header,
> public endpoints like login and register would break. It extends
> `OncePerRequestFilter` because a plain servlet filter can execute several
> times for a single request through forwards or async dispatches, and verifying
> the same token repeatedly is wasteful and unpredictable.

### "401 or 403?"

> 401 means I don't know who you are — authenticate and try again. 403 means I
> know who you are and you're still not allowed. Spring returns 403 by default
> for anonymous access to a protected endpoint, because anonymous technically
> counts as authenticated-with-no-authorities. For an API that's misleading: a
> client seeing 401 knows to refresh its token, whereas 403 implies refreshing
> won't help. I added an `AuthenticationEntryPoint` to return 401 with an
> RFC 7807 problem-detail body, matching the error format the rest of the API
> uses.

### "How do you handle secrets?"

> The signing keys are never in the repository — verified with `git check-ignore`
> rather than assumed. Locally they're generated by a script into a gitignored
> directory. CI generates throwaway keys at the start of each run that die with
> the runner. Production has its own separate key pair, mounted read-only into
> the container, with the path supplied by an environment variable. The same
> image runs in every environment; only the configuration differs. The container
> also runs as a pinned unprivileged UID, and the key file is owned by that UID
> with read-only permissions.