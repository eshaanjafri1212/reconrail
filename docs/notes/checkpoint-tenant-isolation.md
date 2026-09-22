# Checkpoint: Tenant isolation enforced (Phase 1)

**Completed:** 22 Sep 2026
**Covers:** tenant context propagation, Hibernate tenant filter, PostgreSQL
row-level security, the restricted application database role.
**Related:** ADR-0003 (multi-tenancy strategy).

---

## Part 1 — The problem, in one paragraph

After authentication worked, the system knew *who* was calling — but nothing
stopped the code from reading *another tenant's* data. Isolation depended on
every repository method remembering to pass the right `tenantId`. One
forgotten filter — a `findAll()`, a new report, a hand-written SQL query —
would silently return every seller's rows. For a product holding settlement
and revenue data, that is the worst bug possible. ADR-0003 answered it with
three independent layers, so that forgetting becomes impossible rather than
merely discouraged.

---

## Part 2 — The three layers, and what each one actually does

### Layer 1 — Tenant context (`TenantContext`, a ThreadLocal)

**What:** a per-thread holder for the current request's tenant id, set by
`JwtAuthenticationFilter` from the verified token's `tid` claim.

**Why a ThreadLocal:** Tomcat handles each request on one thread from start
to finish, so "this request's tenant" and "this thread's tenant" are the same
thing. Code deep in the call stack can ask *which tenant?* without the value
being passed through every method signature. Spring's own
`SecurityContextHolder` uses exactly this pattern.

**The danger:** Tomcat reuses threads from a pool. A thread that served
tenant 3 will later serve tenant 9. If the value isn't cleared, tenant 9's
request inherits tenant 3's context. So clearing happens in a `finally` block
around `filterChain.doFilter(...)` — it runs whether the request succeeds,
throws, or returns early. `remove()` is used rather than `set(null)`, since
`remove()` detaches the entry entirely instead of leaving a lingering one.

**The tenant id comes only from the signed token** — never a header or request
parameter, or a client could simply claim to be another tenant.

**Layer 1 alone enforces nothing.** It is plumbing for the other two.

### Layer 2 — Hibernate tenant filter (`@Filter` + `TenantFilterAspect`)

**What:** `@FilterDef`/`@Filter` on `AppUser` and `RefreshToken` declare a
condition (`tenant_id = :tenantId`). When enabled on a Hibernate session,
Hibernate appends it to every query on those entities.

**Why an aspect enables it:** the filter must be switched on for an *open*
Hibernate session, and sessions are bound to transactions. The servlet filter
runs too early (no session yet); enabling it in each repository call would be
scattered and forgettable. An AOP aspect runs automatically before every
service method.

**AOP in one sentence:** Spring hands callers a *proxy* instead of the real
service object; when a method is called, the proxy runs the aspect's code
first, then the real method. `@Transactional` works the same way.

**Ordering matters:** the aspect must run *inside* the transaction.
`@EnableTransactionManagement(order = 0)` plus `@Order(1)` on the aspect
guarantees transaction-then-aspect nesting. Left unordered, it may work by
accident and break later.

**Its gaps:** Hibernate filters do **not** apply to `find()` by primary key,
nor to native SQL queries. Those gaps are why Layer 3 exists.

### Layer 3 — PostgreSQL row-level security

**What:** a *policy* attached to a table, which the database applies to every
query — `SELECT`, `UPDATE`, `DELETE`, `INSERT` — from any client, through any
ORM, including raw SQL. Rows belonging to other tenants become invisible.

**The policy, piece by piece:**

```sql
CREATE POLICY tenant_isolation_app_user ON app_user
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
```

- `USING (...)` — a condition evaluated per row; only rows where it's true exist.
- `current_setting('app.tenant_id', true)` — reads a custom session variable;
  `true` returns NULL instead of erroring when unset.
- `NULLIF(..., '')` — treats an empty string as NULL.
- `::bigint` — casts the text setting to the column's type.
- **Fail closed:** with no tenant set, the condition is `tenant_id = NULL`,
  which is never true. No context means no rows — a bug that forgets to set
  the tenant shows nothing rather than everything.

**`USING` also checks writes.** Without a separate `WITH CHECK` clause, the
same condition validates inserted and updated rows. A row whose `tenant_id`
doesn't match the session's tenant is rejected with
`new row violates row-level security policy`.

**Setting the variable — `set_config`, not `SET LOCAL`:**

```sql
SELECT set_config('app.tenant_id', ?, true)
```

`SET LOCAL` cannot take a bind parameter (it's a utility statement, not a
query), so building it would mean string concatenation — an injection risk.
`set_config` accepts a parameter, and its third argument `true` makes the
value **transaction-scoped**: PostgreSQL resets it at commit or rollback.
That matters because HikariCP reuses connections across requests; a
session-scoped value would leak into whichever request borrows the connection
next. The same thread-reuse hazard as the ThreadLocal, one layer down — solved
here with no cleanup code at all.

**Where it's set:** by the aspect for authenticated requests, and explicitly
in `login()` and `register()` — those run before any tenant context exists,
so the aspect skips them.

### Why the `tenant` table has no policy

It's the directory of who exists. Login must look a tenant up by slug
*before* a tenant context exists — that lookup is how the context gets
established. Registration must check slug uniqueness across *all* tenants.
Filtering it would make login impossible and duplicate detection useless.
**The table that tells you which tenant you are cannot itself be
tenant-scoped.** It is protected by access control instead: no endpoint lists
tenants, and a slug alone grants nothing without the password.

### The restricted application role

The app now connects as `reconrail_app` — `NOSUPERUSER NOBYPASSRLS`, with only
`SELECT/INSERT/UPDATE/DELETE` on tables and `USAGE` on sequences. Flyway still
runs as `reconrail`, since migrations need DDL rights. The role's password is
injected into the migration through a Flyway placeholder, so it never appears
in SQL.

Beyond making RLS work, this is **least privilege**: a SQL injection bug in a
superuser-connected app can drop the database; in a restricted-role app it
cannot.

---

## Part 3 — Problems we hit

### 1. RLS did nothing — the superuser bypass (the big one)

**What happened.** V004 applied successfully, the app worked perfectly — and
querying `app_user` with tenant 1 set still returned all four users.

**Why.** The official PostgreSQL Docker image creates `POSTGRES_USER` as a
**superuser**, and superusers bypass row-level security **unconditionally**.
`FORCE ROW LEVEL SECURITY` only removes the exemption for ordinary table
owners — it has no effect on superusers. Every query had skipped the policies.

**How we found it.** Not from any error — there wasn't one. From running the
*negative* test: asking the database to show only tenant 1 and counting the
rows. `SELECT rolsuper, rolbypassrls FROM pg_roles` then confirmed
`reconrail` was `t`/`t`.

**The fix.** A separate non-superuser role for the application.

**The lesson.** *A security layer that appears to work while doing nothing is
worse than no layer, because it creates false confidence. The app succeeding
proves nothing about isolation — only a test that tries to break isolation
does.*

### 2. The variable name mismatch

Java set `app.tenantId`; the policy read `app.tenant_id`. PostgreSQL treats
them as two different variables, so the policy always saw an empty value.
Harmless while the superuser bypassed everything — the moment RLS became real,
registration failed with `new row violates row-level security policy`.

**The lesson.** *A string that must match exactly in two places, with nothing
verifying they agree, is a bug waiting to happen.* It now lives in one Java
constant, with a comment in the migration naming it.

### 3. `spring-boot-starter-aop` doesn't exist in Boot 4.1

Adding it failed with a missing version, then "dependency not found". The
starter was restructured in the Boot 3→4 transition. Replaced by
`aspectjweaver` directly — which is all the starter ever provided on top of
`spring-aop`, already present via the data-JPA starter.

### 4. Smaller ones

- **Missing semicolons** in the migration made two `ALTER` statements parse as
  one malformed statement.
- **`@Configuration` used where `@Component` belonged.** It worked (one
  includes the other) but they mean different things: `@Configuration` marks a
  class that *defines* beans and gets specially proxied.
- **The ThreadLocal test didn't truly test thread reuse.** Tomcat happened to
  assign a different thread to every request, so the scenario the `finally`
  protects against was never exercised. Absence of evidence, not proof — a
  forced-reuse integration test is still owed.

### 5. Should we have built RLS at all?

We stopped and debated it, and the reasoning is worth keeping. **Against:**
one application, no native queries yet, silent failures that are hard to
debug, a solo maintainer. **For:** money data, six services planned against
this database, hand-written SQL arriving with recon-engine in Phase 3 —
precisely where Layers 1 and 2 stop working. Kept, as a safety net rather than
the primary mechanism. If this were a single-service app that would never
grow, skipping it would have been the right call.

---

## Part 4 — Costs we accepted

- **Two database credentials in every environment** instead of one.
- **Legitimate cross-tenant work needs an explicit escape hatch.** Background
  jobs (e.g. expired-token cleanup), analytics, and a future admin dashboard
  will see nothing through the app role. Planned answer: a separate
  `BYPASSRLS` role with its own credentials, used only by those jobs — a
  distinct database identity is harder to trigger accidentally than a policy
  flag a bug might set.
- **Silent failure mode.** Misconfiguration shows up as zero rows, not an
  error, and the cause is invisible in a Java stack trace.
- **Rollback now spans image and config.** Reverting to an image from before
  this change also requires restoring the old datasource environment variables.
- **Debugging in psql** requires setting the variable first, or connecting as
  the superuser.

---

## Part 5 — Interview questions

### "How do you guarantee one tenant can never see another's data?"

> Three independent layers, each assuming the ones above it might fail. First,
> the tenant id comes only from the verified JWT and is held in a ThreadLocal
> for the request, cleared in a finally block because Tomcat reuses threads.
> Second, a Hibernate filter enabled by an aspect appends the tenant condition
> to every ORM query, so forgetting a WHERE clause isn't possible on a normal
> repository call. Third, PostgreSQL row-level security enforces it inside the
> database itself, so even a primary-key lookup or a raw SQL query can't cross
> the boundary. The application connects as a restricted role specifically so
> the database policies apply to it.

### "Why do you need the database layer if the ORM already filters?"

> Because the ORM filter has known gaps — it doesn't apply to find-by-primary-
> key or to native queries — and the reconciliation engine will need hand-
> written SQL for performance. It's also the only layer that holds if the
> application itself is wrong. The first two layers are the day-to-day
> mechanism; row-level security is the safety net that makes a bug in them a
> non-event rather than a breach.

### "What went wrong when you built it?"

> RLS silently did nothing at first. The migration applied, the app worked,
> and I only caught it because I ran a negative test — set the tenant to one
> value and counted the rows — and got all of them back. The official Postgres
> Docker image creates its default user as a superuser, and superusers bypass
> row-level security unconditionally; FORCE only affects ordinary owners. The
> fix was a separate non-superuser role for the application, which is least
> privilege anyway. The real lesson was that a security control appearing to
> work proves nothing — only a test that tries to break it does.

### "Why `set_config` and not `SET LOCAL`?"

> Two reasons. SET LOCAL can't take a bind parameter, so I'd have had to build
> the statement by concatenation, which is an injection risk. And set_config
> with its third argument true is transaction-scoped, so the value resets
> automatically at commit. That matters with a connection pool — a
> session-level setting would leak into the next request that borrows the
> connection, which is the same thread-reuse hazard as the ThreadLocal, just at
> the database layer.

### "Isn't row-level security overkill?"

> It can be, and I weighed that explicitly. For a single application with no
> raw SQL, it adds silent failure modes for little benefit. I kept it because
> the product holds financial data and the roadmap has several services and
> hand-written SQL against the same database — exactly where ORM-level
> filtering stops working. I'd skip it on a small single-service app.

### "How does your cleanup job work if RLS hides everything?"

> It would need its own database identity with BYPASSRLS, rather than a flag in
> the policy. Putting the privilege in who connects, instead of in a session
> variable the application sets, means a bug in normal request handling can't
> accidentally disable isolation.

### "Why does the tenant table have no policy?"

> It's the directory you consult to find out which tenant you are. Login looks
> a tenant up by slug before any tenant context exists, and registration must
> check uniqueness across all tenants. Filtering it would make login
> impossible. It's protected by access control instead — nothing exposes a
> list of tenants, and a slug alone grants nothing without credentials.