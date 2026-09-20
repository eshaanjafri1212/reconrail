# Operations cheat sheet

Quick reference for returning to this project after a gap. Everything here is
"where things live and how to poke at them", not architecture — see
`docs/notes/` for the why and `docs/runbooks/` for procedures.

> **Notation:** `[LOCAL]` = the laptop (WSL or Git Bash). `[VM]` = the Oracle
> server. Check the shell prompt before running anything: `eshaanjafri@jafri`
> is local, `ubuntu@reconrail-vm` is production. Many commands are identical in
> both places, which is exactly why this matters.

---

## Connecting

```bash
# [LOCAL] ssh to production
ssh -i ~/.ssh/reconrail_oci ubuntu@130.210.49.228
```

The SSH key exists in both Git Bash (`C:\Users\jafri\.ssh\`) and WSL
(`~/.ssh/`). Same key, copied — not two separate keys.

---

## Where every secret lives

Three separate stores, each holding only what that environment needs. Nothing
secret is in git.

| Store | Contains | Read by |
|---|---|---|
| `~/reconrail/.env` on the VM | `POSTGRES_PASSWORD`, `IMAGE_TAG` | Docker Compose on the server |
| `~/reconrail/keys/` on the VM | production RSA key pair | the auth-service container |
| GitHub → Settings → Secrets | `VM_SSH_KEY`, `VM_HOST`, `VM_USER` | the CI runner, to deploy |
| `services/auth-service/src/main/resources/keys/` locally | dev RSA key pair | local runs; gitignored, regenerable |

**`${POSTGRES_PASSWORD}` in the compose file resolves from `.env`**, not from
GitHub. Docker Compose automatically reads a file named `.env` in the same
directory as `docker-compose.yml` and substitutes `${VAR}` references.

Locally the compose file uses `${POSTGRES_PASSWORD:-localdev}` — the `:-`
supplies a default, so local dev needs no `.env` at all.

Regenerate dev keys any time: `[LOCAL] bash scripts/generate-dev-keys.sh`

---

## Files on the production VM

| Path | What it is |
|---|---|
| `~/reconrail/docker-compose.yml` | production stack — pulls from ghcr, mounts keys, binds ports to localhost |
| `~/reconrail/.env` | DB password + deployed image tag (mode 600) |
| `~/reconrail/keys/private.pem` | JWT signing key — owner UID 10001, mode 400 |
| `~/reconrail/keys/public.pem` | JWT verification key, mode 444 |
| `/etc/nginx/conf.d/reconrail.conf` | reverse proxy: TLS, HTTP→HTTPS redirect, location blocks |
| `/etc/nginx/certs/reconrail.pem` | Cloudflare Origin certificate |
| `/etc/nginx/certs/reconrail.key` | its private key (mode 600) |
| `~/.ssh/authorized_keys` | two keys: personal access + CI deploy key |
| `/etc/iptables/rules.v4` | persisted host firewall rules |

Files on the laptop that are **not** in git: the dev `keys/*.pem`, and
`~/.ssh/reconrail_oci` / `~/.ssh/reconrail_deploy`.

---

## Talking to the database

```bash
# one-off query (works identically local and [VM] — check your prompt!)
docker exec -it reconrail-postgres psql -U reconrail -d authdb -c "SELECT * FROM tenant;"

# interactive session — better for exploring
docker exec -it reconrail-postgres psql -U reconrail -d authdb
```

Command breakdown: `docker exec` runs a command inside an already-running
container · `-i` keeps input open · `-t` allocates a terminal · then the
container name · then `psql` with `-U` user, `-d` database, `-c` a single
command to run and exit.

Inside the psql prompt:

| Command | Does |
|---|---|
| `\dt` | list tables |
| `\d app_user` | describe a table (columns, indexes, constraints) |
| `\x` | toggle expanded output — much more readable for wide rows |
| `\q` | quit |

Useful queries:

```sql
-- who exists
SELECT u.id, u.email, u.role, t.slug FROM app_user u JOIN tenant t ON t.id = u.tenant_id;

-- active sessions (left() just truncates long strings for readability)
SELECT id, tenant_id, ip_address, left(user_agent, 40), expires_at, revoked_at
FROM refresh_token ORDER BY id DESC LIMIT 10;

-- which migrations have run
SELECT version, description, success, installed_on FROM flyway_schema_history;
```

---

## Containers and images

```bash
cd ~/reconrail                    # [VM] — compose commands need the right directory
docker compose ps                 # what's running and healthy
docker compose logs -f auth-service   # follow logs (Ctrl+C to stop)
docker compose up -d              # start / apply changes
docker compose restart auth-service
docker stats                      # live CPU and memory per container
```

```bash
# [LOCAL] typical dev setup — database in Docker, app from Maven
cd infra && docker compose up -d postgres
cd ../services/auth-service && mvn spring-boot:run
```

Running the *whole* local stack and the Maven app at once causes a port
conflict on 8081. Start only `postgres` for day-to-day work.

**Image housekeeping.** Every deploy pulls a new SHA-tagged image and they
accumulate. `docker image prune -f` does **not** remove them — it only removes
dangling (untagged) images.

```bash
docker system df                              # real usage, with a reclaimable column
docker image prune -af --filter "until=168h"  # unused images older than 7 days
```

Layers are shared between images, so twenty images at "522MB" each is nowhere
near 10GB of actual disk. Check `docker system df` before worrying.

---

## Nginx

```bash
# [VM]
sudo nano /etc/nginx/conf.d/reconrail.conf
sudo nginx -t                     # ALWAYS test before applying
sudo systemctl reload nginx       # applies without dropping connections
sudo systemctl status nginx
sudo tail -30 /var/log/nginx/error.log
```

`location /api/` matches everything **starting with** that prefix, so a new
controller under `/api/v1/anything` needs no Nginx change. A controller on a
different root (e.g. `/reconrail/v1/...`) would need its own block — which is
a good reason to keep every endpoint under `/api/`.

Currently proxied: `/actuator/health`, `/api/`. Everything else returns the
placeholder.

---

## Testing the live API

```bash
# [LOCAL]
curl -s https://reconrail.in/actuator/health; echo

# capture a token (install jq first: sudo apt install -y jq)
TOKEN=$(curl -s -X POST https://reconrail.in/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantSlug":"...","email":"...","password":"..."}' | jq -r '.accessToken')

curl -s https://reconrail.in/api/v1/auth/me -H "Authorization: Bearer $TOKEN" | jq
```

Without `jq`, the crude equivalent is
`| grep -o '"accessToken":"[^"]*' | cut -d'"' -f4` — grep `-o` prints only the
matched text, then cut splits on `"` and takes field 4. `jq` is worth
installing.

`$(...)` is command substitution: run the command, capture its output into the
variable. `; echo` after a curl just adds a newline so responses don't run
together on one line.

---

## Container registry

Images live at `ghcr.io/eshaanjafri1212/auth-service`, visible under
**Packages** on the GitHub profile. The name reads
`registry / owner / image : tag`, and the tag is the **commit SHA** — so the
running container always traces back to an exact commit.

**Rollback:** edit `IMAGE_TAG` in `~/reconrail/.env` to a previous SHA, then
`docker compose pull && docker compose up -d`.

---

## Debugging connectivity: inside out

Never guess which layer is broken.

| Step | Command | Failure means |
|---|---|---|
| 1 | `[VM] curl http://127.0.0.1:8081/actuator/health` | the app or its container |
| 2 | `[VM] curl -k https://localhost/actuator/health` | Nginx config or upstream |
| 3 | `[LOCAL] curl -k https://130.210.49.228/actuator/health` | a firewall layer |
| 4 | `[LOCAL] curl https://reconrail.in/actuator/health` | Cloudflare or DNS |

Four firewall layers must all permit traffic: Cloudflare → OCI security list
(subnet) → OCI NSG (VNIC) → host iptables. The last is the one people forget:

```bash
# [VM]
sudo iptables -L INPUT -n --line-numbers    # ACCEPT rules must sit ABOVE the REJECT
```

Cloudflare error codes: **521** origin refused · **522** timed out (firewall) ·
**523** unreachable (firewall or wrong DNS record) · **526** origin certificate
invalid. A **502** comes from Nginx itself and means the upstream app didn't
answer.

---

## Local addresses and ports

| Thing | Where |
|---|---|
| auth-service (local, Maven) | `http://localhost:8081` |
| PostgreSQL (local, Docker) | `localhost:5432` |
| auth-service (VM) | `127.0.0.1:8081` — **not** reachable externally by design |
| PostgreSQL (VM) | no published port; Docker network only |

On the VM, ports are bound to `127.0.0.1` deliberately: Docker writes its own
iptables rules, and a bare `8081:8081` would publish the port to the internet
**bypassing the host firewall**.