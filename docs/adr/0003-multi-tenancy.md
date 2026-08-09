# ADR-0003: Multi-tenancy strategy — shared schema with tenant discriminator

- **Status:** accepted
- **Date:** 09 Aug 2026
- **Deciders:** Eshaan Jafri
- **Supersedes:** none

## Context and Problem Statement

ReconRail is a B2B SaaS product: one running system serves many independent
sellers (tenants). Every seller's orders, settlement events, discrepancies,
claims and recovered amounts must be visible only to that seller. Because the
product handles money data, a cross-tenant leak is not a defect to be patched
later — it is an existential failure of the product's core promise.

The question is **where the isolation boundary is drawn**: at the database, at
the schema, or at the row. This must be decided before the first tenant-scoped
table is created, because retrofitting isolation onto existing production data
is expensive and error-prone.

## Decision Drivers

- **Isolation strength.** Money data. Leakage across tenants is unacceptable.
- **Infrastructure constraint.** The entire system runs on a single OCI Always
  Free ARM VM: 2 OCPUs, 12 GB RAM, one PostgreSQL instance (NFR-08).
- **Cross-tenant analytics is a product feature, not an afterthought.** The
  Money Leak Meter and benchmarking ("sellers on Flipkart recover X% on
  average") require aggregate queries spanning all tenants.
- **Schema evolution.** Marketplace formats change; migrations must be routine
  and low-risk (NFR-09).
- **Onboarding cost.** A pilot seller should be onboarded in seconds, not by
  provisioning infrastructure.
- **Operational load.** Solo developer; operational complexity has to stay
  proportionate.

## Considered Options

### 1. Database per tenant

Each tenant gets a physically separate database.

- **Good:** strongest possible isolation — a query cannot reach another
  tenant's data because it is a different connection. Per-tenant backup and
  restore. No noisy-neighbour effects. Straightforward data residency and
  clean exit ("here is your database").
- **Bad:** every migration runs N times, and partial failure leaves tenants on
  divergent schema versions. Connection pools multiply — one pool per database
  does not fit in 12 GB beyond a handful of tenants. Cross-tenant analytics
  becomes a distributed query problem. Onboarding means provisioning
  infrastructure.
- **Verdict:** ruled out by the infrastructure constraint alone, and by the
  fact that cross-tenant aggregation is a product requirement.

### 2. Schema per tenant

One database, one PostgreSQL schema per tenant, selected per connection via
`search_path`.

- **Good:** meaningful isolation with one database to operate; shared
  connection pool; per-tenant export is still reasonably easy.
- **Bad:** still N migration runs. PostgreSQL degrades past a few thousand
  schemas (system catalog bloat, slow dumps, awkward tooling). Cross-tenant
  queries require UNION across schemas or a separate reporting pipeline.
- **Verdict:** carries most of the operational cost of option 1 without
  delivering its isolation guarantee.

### 3. Shared schema with a `tenant_id` discriminator column

One database, one schema; every tenant-scoped table carries `tenant_id`, and
every query is filtered on it.

- **Good:** one migration run; one connection pool; onboarding is an INSERT;
  cross-tenant analytics is a `GROUP BY`; scales to very large tenant counts;
  fits the single-VM constraint.
- **Bad:** **isolation is enforced by application code, not by physical
  separation.** A single missing `WHERE tenant_id = ?` is a data breach. A
  large tenant can degrade performance for others (noisy neighbour).

## Decision Outcome

**Chosen: option 3 — shared schema with a `tenant_id` discriminator**, on the
explicit condition that isolation is enforced in **three independent layers**
rather than by developer discipline. The layering is part of the decision, not
an implementation detail: option 3 without it would not be an acceptable choice
for money data.

### Layer 1 — Tenant context resolution

The JWT issued at login carries a `tenant_id` claim. A servlet filter extracts
it once per request and stores it in a `ThreadLocal` holder, clearing it in a
`finally` block.

*Why the `finally` matters:* application servers reuse threads from a pool. A
`ThreadLocal` that is set but never cleared leaks into the next request served
by that thread — which in this design means one tenant silently inheriting
another tenant's context. This is a known bug class and the reason the clearing
is mandatory rather than tidy.

The tenant identity is never read from a request parameter, header or body.
It comes only from the signed token, so a client cannot assert a tenant it has
not authenticated as.

### Layer 2 — Automatic query filtering (Hibernate)

Tenant-scoped entities are annotated with `@FilterDef` / `@Filter`, and the
filter is enabled on the Hibernate session from the tenant context. Hibernate
then appends `tenant_id = :currentTenant` to every generated query.

*Why:* this converts "remember to filter" from a convention into a default.
Forgetting the predicate on a normal repository call becomes impossible rather
than merely discouraged.

### Layer 3 — PostgreSQL Row-Level Security

RLS policies are enabled on tenant-scoped tables, matching rows against a
session variable set per connection.

*Why:* layers 1 and 2 both live inside the application. RLS is enforced by the
database itself, so a native query, a reporting script, an ORM bypass or a
compromised application path still cannot read another tenant's rows. This is
what changes the guarantee from "we are careful" to "it is structurally
prevented" — defence in depth, where each layer assumes the ones above it may
fail.

### Supporting requirements

- `tenant_id` is `NOT NULL` on every tenant-scoped table, introduced in the
  Flyway migration that creates the table — never added later.
- Every index on a tenant-scoped table **leads with `tenant_id`**, e.g.
  `(tenant_id, order_id)`. Since every query filters on tenant first, a
  composite index in that order is what the planner can actually use; an index
  on `order_id` alone would force a scan across all tenants' rows.
- Integration tests must include **negative isolation tests**: authenticate as
  tenant A, attempt to read a record belonging to tenant B by its primary key,
  and assert that nothing is returned. These tests are the executable proof
  that the three layers work, and they run in CI on every pull request.

### Consequences

- **Good:** fits the 12 GB single-host constraint; migrations run once;
  onboarding a pilot seller is trivial; cross-tenant benchmarking (a product
  differentiator) is a simple aggregate query; one connection pool.
- **Bad:** a defect in the tenant-resolution layer is systemic rather than
  contained — the blast radius of a bug is every tenant, not one. Requires
  discipline that the layering makes structural but cannot make free.
- **Bad:** noisy-neighbour risk. One very large seller's reconciliation run can
  degrade query latency for others sharing the same tables and I/O.
- **Revisit when:** (a) a tenant requires contractual physical isolation or
  data residency; (b) the ledger table's growth makes tenant-scoped queries
  slow despite indexing; (c) one tenant's volume measurably degrades others.

### Migration path if revisited

The options are not mutually exclusive permanently. The expected progression is:
partition the ledger table by `tenant_id` inside the shared schema first (a
change local to the database), and only then extract the largest or most
demanding tenants into dedicated databases while the long tail remains shared.
Mature multi-tenant SaaS products commonly end up in exactly this hybrid state,
so choosing option 3 now does not foreclose stronger isolation later for the
tenants that need it.