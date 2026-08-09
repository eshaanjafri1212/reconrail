# Infrastructure deep dive & interview notes (Phase 0–1)

**Purpose:** explain what was built, why each decision was made, and how to
defend it under questioning. Read this before any interview where the project
comes up.

---

## Part 1 — How the whole thing works, end to end

### The cast

| Thing | What it actually is |
|---|---|
| **VM / instance** | A slice of a physical server in Oracle's Mumbai data centre, running its own OS. Behaves like a computer you own, except it's always on and has a public address. |
| **Hosting** | Nothing mystical: your program runs on a machine that has a public IP, so the world can reach it. |
| **Port** | A numbered door on a machine (1–65535) so traffic can say which program it wants. Convention: 22 = SSH, 80 = HTTP, 443 = HTTPS. |
| **Firewall / ingress rule** | A bouncer that only unlocks doors you explicitly name. "Ingress" = inbound, "egress" = outbound. |
| **DNS** | The internet's phonebook: translates names (`reconrail.in`) into numbers (IPs). |
| **Registrar** | The retail shop (Porkbun) authorised to sell entries in a registry. |
| **Registry (DNS)** | The authority for a TLD — NIXI for `.in` — holding the master list of who owns what and which nameservers answer for it. |
| **Nameservers** | The servers holding the actual DNS records for a domain. Pointing them at Cloudflare = *delegating authority*. |
| **A record** | One line in the phonebook: `name → IPv4 address`. |
| **Proxy** | A middleman that receives a request and forwards it on. |
| **Reverse proxy** | A proxy that sits in front of *servers* (Nginx, Cloudflare) rather than in front of clients. |
| **TLS** | The encryption that makes HTTP into HTTPS. Requires a certificate proving the server legitimately speaks for the domain. |
| **Container / image** | An image is a frozen package of software; a container is a running instance of it. Same relation as class → object. |
| **Registry (Docker)** | A server storing images (ghcr.io, Docker Hub) so they can be pulled anywhere. |

### One request, start to finish

Someone in Delhi opens `https://reconrail.in`:

1. **DNS resolution.** The browser asks for the IP. The `.in` registry says
   "Cloudflare is authoritative for this domain." Cloudflare answers — but
   because the record is *proxied* (orange cloud), it deliberately returns
   **Cloudflare's own IP**, not the VM's. The origin IP is never disclosed.
2. **TLS handshake** with Cloudflare's nearest edge server on port 443. Traffic
   is now encrypted.
3. **Proxy hop.** Cloudflare forwards the request over its network to the VM's
   real IP in Mumbai, re-encrypted using the Origin Certificate.
4. **Internet Gateway + NAT.** The VCN's gateway translates the public IP to the
   VM's private address `10.0.0.88`. (The VM itself has no idea what its public
   IP is — the gateway wears that mask on its behalf.)
5. **Firewalls.** Security list (subnet), NSG (VNIC) and host iptables each
   independently permit TCP 443.
6. **Nginx** receives it, terminates TLS, matches the location block, and
   proxies to `127.0.0.1:8081` adding `X-Forwarded-*` headers.
7. **Spring Boot** handles the request; Actuator's health endpoint checks the
   database connection through the Docker network to the `postgres` container.
8. The response travels the same chain in reverse.

### The OCI resource hierarchy

```
Tenancy (the account)
└── Compartment (a folder for resources)
    └── VCN — your private network, CIDR 10.0.0.0/24
        ├── Internet Gateway — the door to the public internet
        ├── Route table — the road signs: 0.0.0.0/0 → IGW
        ├── Security list — subnet-level firewall
        ├── Network Security Group — VNIC-level firewall
        └── Subnet
            └── Instance (VM)
                └── VNIC — the network card; holds the private + public IP
```

**CIDR notation:** `10.0.0.0/24` means the first 24 of 32 bits are fixed, so the
last 8 bits are yours — addresses `10.0.0.0`–`10.0.0.255`. The `10.x.x.x`,
`172.16.x.x` and `192.168.x.x` ranges are reserved *private* ranges that never
route on the public internet, which is why every company can use the same
numbers internally without conflict.

### SSH and asymmetric cryptography

SSH gives you an encrypted remote shell on port 22. Its security rests on a
**key pair**: two mathematically linked keys where what one locks, only the
other opens. You keep the private key; the server holds your public key in
`~/.ssh/authorized_keys`.

The crucial property: the server challenges you to prove you hold the private
key, and you can prove it **without the private key ever crossing the network**.
Compare passwords, where the secret itself must be transmitted and can be
guessed. This is why OCI disables SSH password authentication entirely.

The first-connection fingerprint prompt runs in the *other* direction: it's you
authenticating the *server*. The server has its own host key; its fingerprint is
recorded in `~/.ssh/known_hosts` and verified on every later connection. A
fingerprint that *changes* later is the alarm that matters — it can indicate a
man-in-the-middle.

The same asymmetric-crypto idea appears three times in this project: SSH login,
TLS certificates, and apt's GPG package signing.

---

## Part 2 — Decisions and their trade-offs

### Why microservices when a monolith would do?

Honest answer: for ReconRail's actual load, a modular monolith would suffice.
Microservices were chosen **deliberately as a learning vehicle** and because the
target roles test exactly these skills. The mature framing: *microservices trade
operational complexity for independent deployability and scaling; I chose them
knowing the trade-off, and kept the complexity manageable by running everything
on one host with capped JVM heaps.*

### Why a monorepo? (ADR-001)

Atomic cross-service changes in one PR, one CI config, one place for docs, and
concentrated portfolio history. Cost: CI needs path filters so a frontend change
doesn't rebuild everything, and module boundaries need discipline because the
compiler won't enforce them. Revisit when multiple contributors own separate
services or services need independent release cadences.

### Trunk-based development vs environment branching

At BT: long-lived branches per environment (modelE → modelB → modelA → prelive →
live); *merging into a branch* is what promotes code.

Here: one long-lived branch (`main`), short-lived feature branches, and
**promotion by trigger conditions, not by branch topology**. The same build
artifact moves between environments.

**The distinction worth stating:** a branch is a version of the *code*; an
environment is a place where code *runs*. Gitflow glues them together;
trunk-based separates them. The practical consequence is *build once, deploy
many* — the exact bytes tested are the exact bytes that go live. In the
branching model, what was tested in prelive is a different merge result from
what lands in live, which is the root of "worked in staging, broke in prod."

Neither is wrong. Environment branching suits large organisations with
coordinated, scheduled releases across many teams (telecom, banking).
Trunk-based suits continuous delivery. **Knowing both and articulating the
trade-off is the strong answer.**

### Flyway migrations vs Hibernate auto-DDL

`ddl-auto: validate` + Flyway, never `create` or `update`.

- `create` **drops and recreates** the schema on startup — catastrophic against
  real data.
- `update` only adds; it can't rename, drop, retype safely, or add needed
  indexes, and it makes unreviewed structural changes at boot time.
- Neither is **reviewable** (no diff in a PR) or **reproducible** (environments
  reached by different upgrade paths silently diverge).

Flyway makes each change a numbered SQL file in version control. On startup it
consults `flyway_schema_history`, applies only what's missing in version order,
each in a transaction, and **verifies checksums** — an edited migration fails
startup loudly instead of leaving environments quietly different.

Four properties: reproducible, reviewable, auditable, automated. `validate` is
the guardrail: Hibernate compares entities against the real schema and fails
fast on drift, but never writes. One writer, plus a cross-check.

**Relevance to ReconRail specifically:** the product is a money ledger; the spec
requires an immutable audit trail (NFR-05) and schema evolution as marketplace
formats change (NFR-09). A tool that might silently alter tables is
incompatible with that.

### Filename grammar (a common source of silent failure)

```
V001__create_users_table.sql
│  │  └── description
│  └───── TWO underscores (one underscore = file ignored)
└──────── V = versioned; R = repeatable (views, functions)
```

Zero-padded sequence numbers keep files sorted correctly. Timestamp versions
(`V20260803__`) are the alternative for teams where many people write migrations
in parallel and would otherwise collide on the same number.

### Multi-stage Docker builds

Stage 1 has Maven + JDK + the dependency cache. Stage 2 is a fresh JRE-Alpine
image receiving *only the jar*. Result: ~174 MB instead of ~800 MB, and no
compiler or build tooling on the production host for an attacker to use.

**Layer caching:** each instruction creates a cached layer. `COPY pom.xml` and
`mvn dependency:go-offline` come *before* `COPY src`, so a normal code change
reuses the cached dependency layer. Measured effect in our build: dependency
resolution 41s vs application compile 5.8s — the 41s becomes a cache hit.

**Hardening:** containers run as root by default, so a container escape hands an
attacker root on the host. We create an unprivileged `app` user and `USER app`.

**JVM flags for a constrained host:** `-XX:MaxRAMPercentage=75` makes the heap
size relative to the *container's* limit rather than the host's total RAM;
`-XX:+UseSerialGC` uses less memory and CPU than G1 on small heaps and few
cores. Both exist because the whole system must fit in 2 OCPU / 12 GB (NFR-08).

### Liveness vs readiness

- **Liveness**: is the process healthy or wedged? Failure → restart it.
- **Readiness**: can it serve traffic right now? Failure → stop routing to it,
  but don't kill it.

Conflating them causes outages: a busy service gets killed mid-work, or a
still-booting service gets flooded. Kubernetes formalises this with separate
probes; `depends_on: condition: service_healthy` in Compose is the same idea —
never assume a dependency is up, verify readiness.

### Secrets

The database password exists in exactly one place: `.env` on the VM, `chmod 600`,
outside version control, injected via `${POSTGRES_PASSWORD}`. CI never handles a
long-lived credential — `secrets.GITHUB_TOKEN` is minted per run and destroyed,
scoped by `permissions: packages: write` (least privilege).

---

## Part 3 — Likely interview questions, with answers

### "You said you deployed this yourself. Walk me through it."

> I bought the domain from a registrar, which registers it with the `.in`
> registry. I delegated DNS authority to Cloudflare by replacing the
> registrar's nameservers, so Cloudflare answers all lookups for the domain.
> Separately I provisioned an ARM VM on Oracle Cloud's always-free tier in
> Mumbai — Ubuntu 24.04, 2 OCPUs, 12 GB. I created an SSH key pair locally and
> supplied the public key at instance creation so login is key-only, no
> passwords. Then I made the subnet genuinely public: an internet gateway plus a
> route rule sending `0.0.0.0/0` to it, and a public IP attached to the VNIC.
> I installed Docker, then Nginx as a reverse proxy terminating TLS with a
> Cloudflare origin certificate and proxying to the application on localhost.
> Finally I created a proxied A record pointing the domain at the VM, and
> switched Cloudflare's SSL mode to Full (strict) so both legs of the connection
> are encrypted and validated.

### "What was the hardest part / what went wrong?"

This is the question that actually discriminates between candidates. Use a
specific failure and the *method* you used to resolve it:

> The site returned Cloudflare 522s and then 523s even though the application
> was healthy. Rather than guessing, I tested from the inside out: curl to
> localhost on the app port worked, curl to Nginx on localhost worked, but curl
> to the public IP from my laptop returned "no route to host." That isolated it
> to a network layer rather than the application. It turned out there are four
> independent layers that must all permit the traffic — Cloudflare, the OCI
> subnet security list, the VNIC network security group, and the host's own
> iptables. The last one is the one people miss: Oracle's Ubuntu images ship
> with a rule chain ending in a REJECT, and iptables stops at the first match,
> so my ACCEPT rules for 80 and 443 were dead code because I'd inserted them
> below the REJECT. Moving them above it fixed it, and I persisted them with
> netfilter-persistent because iptables rules are in-memory only.

Other genuine ones worth having ready:

- **CI failed with `./mvnw: Permission denied`, exit 126.** Windows doesn't
  store the Unix executable bit, so the Linux runner couldn't execute the Maven
  wrapper. Fixed with `git update-index --chmod=+x`. *The point:* my laptop
  could never have caught this — CI running on the same OS family as production
  found it in two minutes. That's the value of a clean-room build.
- **Local app authenticated against the wrong database.** An old
  Windows-installed PostgreSQL was squatting on port 5432, so the app never
  reached the Docker container. Diagnosed by reading the error layer by layer:
  the TCP connection succeeded and *authentication* failed, which meant the
  network path was fine and the credentials didn't match — narrowing infinite
  possibilities to two.
- **Free-tier capacity.** ARM instances in Mumbai were repeatedly "out of host
  capacity." Solved by retrying in a low-demand window rather than
  over-engineering around it. Knowing when the answer is patience is also
  engineering judgement.

### "Why not use a managed platform like Heroku/Render/App Runner?"

> Because the operational layer is exactly what I wanted to learn, and because
> the constraint is real: the whole system has to fit in 2 OCPUs and 12 GB at
> zero cost. Managing it myself forced me to understand reverse proxying, TLS
> termination, firewall layering and container resource limits rather than
> having them abstracted away. For a commercial product with a team, I'd weigh
> that differently — managed platforms buy back operational time.

### "Your VM has a public IP. How is it secured?"

> Key-only SSH with password authentication disabled; the private key never
> leaves my machine. Only three ports are open at all — 22, 80, 443 — across
> four firewall layers. Application ports are bound to `127.0.0.1`, so the
> service is unreachable from outside even though Docker is running; that
> matters because Docker writes its own iptables rules and a naive port
> publication silently bypasses host firewall rules. PostgreSQL publishes no
> host port at all and is reachable only over the internal Docker network.
> Containers run as an unprivileged user, not root. The origin IP is hidden
> behind Cloudflare's proxy, which also absorbs volumetric attacks. Secrets live
> in a `chmod 600` env file outside version control.

### "What's the difference between a security list and an NSG?"

> Security lists apply to an entire subnet; NSGs apply to the specific VNICs
> that are members of the group, so you can give different servers in the same
> subnet different rules. When both apply, traffic must satisfy both — they
> intersect, they don't override. NSGs are the finer-grained and generally
> preferred mechanism.

### "Why Cloudflare in front if you already have Nginx?"

> They're the same pattern at different scales. Cloudflare hides the origin IP,
> absorbs DDoS traffic, terminates TLS at an edge close to the user, and caches
> static assets globally. Nginx routes traffic *within* the host to the right
> internal service, which matters because one machine has one port 443 but will
> soon run several services. Nginx also gives me a place to do blue-green
> deployments later by switching upstreams.

### "Describe your CI/CD pipeline."

> Every pull request triggers a build on a clean Ubuntu runner: it installs
> JDK 21, runs `mvnw clean verify` against a real PostgreSQL service container,
> and the check is required before merge. On merge to main, a second job builds
> a multi-architecture image with buildx and QEMU — the runners are x86 but the
> production host is ARM — and pushes it to GitHub Container Registry tagged
> with the commit SHA. A third job then SSHes to the server using a dedicated
> deploy key, pins that SHA in the environment file, pulls and restarts the
> containers, and polls the readiness endpoint for up to two minutes, failing
> the workflow if the service never reports healthy. So a deployment is only
> "successful" if the application actually came up, not merely if the commands
> ran. Rollback is changing one line to a previous SHA and re-running.
### "What would you change to scale this?"

> The single VM is the obvious bottleneck and a single point of failure. First
> step would be splitting the database onto managed Postgres so it scales and
> gets backups independently. Then running multiple application instances behind
> a load balancer, which requires the services to be stateless — they are,
> because sessions will be JWT-based rather than server-side. Beyond that,
> managed Kubernetes when the service count and traffic justify the operational
> overhead. I'd also move from an ephemeral public IP to a reserved one so the
> address survives instance replacement.

### "What is `docker compose` actually giving you here?"

> Declarative orchestration of the local and production topology in one file:
> images, environment, networking, health checks and dependency ordering. The
> user-defined bridge network gives DNS by service name, which is why the
> datasource URL is `jdbc:postgresql://postgres:5432/...` — no IP addresses
> anywhere. That's service discovery in its simplest form; the same conceptual
> role Eureka or Consul would play in a larger deployment.

- **A public IP that wouldn't attach.** The create-instance form silently
  refused to assign a public IPv4 address, and afterwards the VNIC showed
  "not assigned" with no error explaining why. The UI gave no useful signal, so
  I reasoned from first principles about what makes a subnet genuinely public:
  it needs an internet gateway, a route rule sending 0.0.0.0/0 to that gateway,
  and a public IP on the VNIC. The gateway existed but nothing routed to it —
  a door with no road leading to it — so the platform correctly refused to
  assign an address that could never have worked. *The point:* the error
  message was useless, but knowing the three required components made the
  missing one obvious.
---

## Part 4 — Quick reference

**Test the stack from the inside out:**

```bash
[VM]    curl http://127.0.0.1:8081/actuator/health   # app
[VM]    curl -k https://localhost/actuator/health    # nginx
[LOCAL] curl -k https://<VM_IP>/actuator/health      # firewalls
[LOCAL] curl https://reconrail.in/actuator/health    # cloudflare + DNS
```

**Concepts this project has already demonstrated:** DNS delegation, reverse
proxying, TLS termination and certificate trust, NAT, CIDR addressing, layered
firewalls, asymmetric cryptography (SSH, TLS, package signing), container
images and layer caching, multi-architecture builds and CPU emulation,
artifact registries, immutable deploy artifacts, database migration discipline,
liveness vs readiness, least-privilege credentials, and reproducible builds.