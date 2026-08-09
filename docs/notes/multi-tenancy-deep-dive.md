# Multi-tenancy — interview notes

> Append this to `docs/infrastructure-deep-dive.md` under Part 3.

---

### "How do you isolate one customer's data from another's?"

This is the question that separates people who followed a tutorial from people
who made a decision. The weak answer is "I have a tenant_id column." The strong
answer names the alternatives, the constraint that ruled them out, and the fact
that a discriminator column alone is not a security control.

> There are three standard approaches: a database per tenant, a schema per
> tenant, or a shared schema with a tenant_id discriminator on every row. I
> chose the shared schema, for two reasons. The infrastructure constraint is
> that everything runs on a single 12 GB host with one PostgreSQL instance, so
> database-per-tenant isn't physically available — and connection pools alone
> would exhaust the memory budget. The product reason is stronger: cross-tenant
> benchmarking is a feature, not a report. The Money Leak Meter tells a seller
> how their recovery rate compares to similar sellers, which is a simple
> GROUP BY in a shared schema and a distributed query problem in the other two.
>
> But the discriminator column is the cheap part. Isolation there is enforced
> by application code, and one forgotten WHERE clause is a breach — so I
> enforce it in three independent layers. The tenant comes from a signed JWT
> claim, never from a request parameter, and a filter puts it in a ThreadLocal
> that's cleared in a finally block. Hibernate filters append the tenant
> predicate to every generated query, so forgetting it isn't possible on a
> normal repository call. And PostgreSQL row-level security enforces it at the
> database, so even a native query or a compromised application path can't
> cross the boundary. Each layer assumes the ones above it might fail.

### "Why the `finally` block? What happens if you skip it?"

> Application servers reuse threads from a pool. If a ThreadLocal is set and
> never cleared, the next request handled by that thread inherits the previous
> request's tenant context. In this design that means one customer silently
> seeing another customer's data — and it would be intermittent and
> load-dependent, so it's the kind of bug you don't reproduce in testing.

### "How would you prove the isolation actually works?"

> Negative tests in CI. Authenticate as tenant A, then attempt to fetch a
> record that belongs to tenant B by its primary key, and assert the result is
> empty rather than forbidden — the row should be invisible, not merely
> refused. Those tests run on every pull request, so isolation is a build
> gate rather than a code-review hope.

### "What's the weakness of your approach?"

Never answer "none." Naming the failure mode is what demonstrates you
understand it.

> Two. First, the blast radius: because isolation is logical rather than
> physical, a defect in the tenant-resolution layer affects every tenant at
> once instead of being contained to one. That's the trade I accepted, and
> it's why the enforcement is layered rather than trusted to one mechanism.
> Second, noisy neighbours — a single high-volume seller's reconciliation run
> shares tables and I/O with everyone else, so it can degrade their latency.

### "How would you fix that later, if a big customer complained?"

> The strategies aren't mutually exclusive. The first step is partitioning the
> ledger table by tenant_id, which is contained within the database and doesn't
> change application code. If that isn't enough, I'd extract the largest
> tenants into dedicated databases while the long tail stays shared — the
> hybrid model most mature multi-tenant SaaS products converge on. Choosing a
> shared schema now doesn't foreclose stronger isolation later for the tenants
> that actually need it.

### "Why does every index start with `tenant_id`?"

> Because every query filters on tenant first. In a composite index the column
> order determines what the planner can use — an index on (tenant_id, order_id)
> serves both a tenant-scoped lookup and a tenant-plus-order lookup, whereas an
> index on order_id alone would make the database scan across every tenant's
> rows before filtering. In a shared-schema design, tenant_id is effectively
> part of the primary access path for every table.

### "Where does the tenant identity come from?"

> The JWT claim, and only the JWT claim. Never a header, query parameter, or
> request body field — otherwise a client could simply assert a different
> tenant and the entire isolation model collapses. The token is signed, so the
> claim can't be forged without the signing key.