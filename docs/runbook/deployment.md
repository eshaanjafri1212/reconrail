# Runbook: Deploying ReconRail to production

**Last verified:** 09 Aug 2026 · **Owner:** Eshaan Jafri
**Outcome:** `https://reconrail.in/actuator/health` returns `{"status":"UP"}`,
served by auth-service running in Docker on the OCI ARM VM, behind Nginx and
Cloudflare.

> **Notation:** every command is labelled with the machine it runs on.
> `[LOCAL]` = your laptop (WSL or Git Bash). `[VM]` = the Oracle server,
> reached via SSH — the prompt must read `ubuntu@reconrail-vm`.

---

## 1. The deployment model

We use **build once, deploy many**: a commit is built and tested exactly once,
producing an immutable Docker image tagged with the commit SHA. That same image
is what runs in production. No environment ever rebuilds from source.

```
git push
  → GitHub Actions runner (fresh Ubuntu x86 VM)
      → clone repo, install JDK 21
      → ./mvnw clean verify   (unit + integration tests against a
                               real Postgres service container)
      → docker buildx build --platform linux/amd64,linux/arm64
      → docker push ghcr.io/eshaanjafri1212/auth-service:<sha> and :latest
  → VM pulls that image and runs it
      → Nginx (:443) reverse-proxies to 127.0.0.1:8081
      → Cloudflare proxies the public internet to the VM
```

**Why multi-arch:** GitHub runners are x86-64; the OCI VM is ARM64 (Ampere).
An x86-only image fails on the VM with `exec format error`. `docker/setup-qemu-action`
provides CPU emulation so the x86 runner can compile ARM binaries; buildx
produces a manifest list, and the VM automatically pulls the arm64 variant.
Trade-off: emulated ARM builds are ~3-5x slower, mitigated by GitHub Actions
layer caching (`cache-from/to: type=gha`).

---

## 2. Artifact registry

Images live in **GitHub Container Registry** (`ghcr.io`), free for public repos.

- Image: `ghcr.io/eshaanjafri1212/auth-service`
- Tags: `<commit-sha>` (immutable identity — deploy this) and `latest`
  (moving pointer — convenience only).
- Auth in CI uses `secrets.GITHUB_TOKEN`, which GitHub mints per workflow run
  and destroys afterwards. The job declares `permissions: packages: write`
  (least privilege). No long-lived credential is stored.
- The package must be set to **public** (Package settings → Change visibility),
  otherwise the VM needs registry credentials to pull.

---

## 3. Production layout on the VM

```
~/reconrail/
├── docker-compose.yml     # pulls from ghcr, no build context
└── .env                   # POSTGRES_PASSWORD, IMAGE_TAG — chmod 600, never in git
```

Key differences from the local compose file, and why:

| Setting | Production value | Reason |
|---|---|---|
| `image:` vs `build:` | `image: ghcr.io/...` | production pulls the tested artifact, never compiles |
| `restart:` | `unless-stopped` | survives crashes and VM reboots (NFR-01) |
| auth-service ports | `127.0.0.1:8081:8081` | reachable only from inside the VM; Nginx proxies to it |
| postgres ports | not published at all | reachable only over the Docker network by service name |
| secrets | `${POSTGRES_PASSWORD}` from `.env` | credential exists in exactly one place, outside version control |

**Critical security note:** the `127.0.0.1:` prefix is not cosmetic. Docker
writes its own iptables rules and a bare `8081:8081` publishes the port to the
whole internet, **bypassing host firewall rules**. Always bind internal
services to localhost.

Generate the DB password with `openssl rand -base64 24`.

---

## 4. Manual deploy  and Automated deploy (normal path)
merge to main → CI tests → publishes multi-arch image → deploy job SSHes in, pins IMAGE_TAG to the commit SHA in .env, pulls, restarts, prunes old images, and polls the readiness endpoint for up to 120s, failing the workflow if it never goes healthy. Note the credential model: a dedicated deploy key (not your personal key) whose private half lives in VM_SSH_KEY, plus VM_HOST and VM_USER secrets.
```bash
# [LOCAL] connect
ssh -i ~/.ssh/reconrail_oci ubuntu@130.210.49.228

# [VM] pull the new image and roll it out
cd ~/reconrail
docker compose pull
docker compose up -d
docker compose ps            # both services should read: Up (healthy)
curl http://127.0.0.1:8081/actuator/health
```
the existing commands, reframed as what you do when CI is unavailable or when rolling back — set IMAGE_TAG to a previous known-good SHA and re-run docker compose pull && up -d.

To deploy a specific commit rather than `latest`, set `IMAGE_TAG=<sha>` in
`.env` before `docker compose pull`. This is also the rollback procedure:
set `IMAGE_TAG` to the previous known-good SHA and re-run.

Useful operational commands `[VM]`:

```bash
docker compose logs -f auth-service     # follow logs
docker compose down                     # stop, keep data volume
docker compose down -v                  # stop and DELETE the volume (data loss)
docker stats                            # live memory/CPU per container
```

---

## 5. Nginx (reverse proxy + TLS termination)

Config: `/etc/nginx/conf.d/reconrail.conf`. Certificates: `/etc/nginx/certs/`.

```nginx
server {
    listen 80;
    server_name reconrail.in www.reconrail.in;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name reconrail.in www.reconrail.in;

    ssl_certificate     /etc/nginx/certs/reconrail.pem;
    ssl_certificate_key /etc/nginx/certs/reconrail.key;

    location /actuator/health {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        return 200 "ReconRail — coming soon\n";
        add_header Content-Type text/plain;
    }
}
```

**Why the `proxy_set_header` lines matter:** without them the application sees
every request as originating from `127.0.0.1` over plain HTTP. `X-Forwarded-For`
carries the real client IP (needed for rate limiting and audit logs);
`X-Forwarded-Proto` tells Spring the user's connection was HTTPS, which Spring
Security needs to generate correct redirect URLs.

**Always test before reloading** — a bad config on a live server means downtime:

```bash
# [VM]
sudo nginx -t
sudo systemctl reload nginx
```

### TLS: Cloudflare Origin Certificate

Traffic has two legs. Browser→Cloudflare is encrypted with Cloudflare's public
certificate. Cloudflare→VM is encrypted with an **Origin Certificate** — a free
certificate Cloudflare issues that only Cloudflare trusts, valid 15 years, no
renewal automation needed (unlike Let's Encrypt's 90 days).

Generated at: Cloudflare → SSL/TLS → Origin Server → Create Certificate.
Installed as `/etc/nginx/certs/reconrail.pem` (certificate) and
`reconrail.key` (private key, `chmod 600`).

Cloudflare SSL/TLS mode is set to **Full (strict)** — Cloudflare validates the
origin certificate rather than trusting any certificate blindly. "Flexible"
would leave the second leg unencrypted; "Full" encrypts but does not validate.

---

## 6. The four firewall layers

A packet from the internet must be permitted by **all four** independently.
This is the single most important thing to know when debugging connectivity.

| # | Layer | Where it's configured | Scope |
|---|---|---|---|
| 1 | Cloudflare proxy | Cloudflare dashboard (DNS record orange cloud) | the public name |
| 2 | OCI Security List | VCN → Security Lists → Default | the whole subnet |
| 3 | OCI Network Security Group | VCN → Network Security Groups → `ig-quick-action-NSG` | the specific VNIC |
| 4 | Host iptables | on the VM itself | the operating system |

Required ingress on layers 2, 3, 4: TCP 22 (SSH), TCP 80, TCP 443.

**Host iptables detail** — OCI's Ubuntu images ship with a rule chain ending in
`REJECT ... icmp-host-prohibited`. iptables evaluates top to bottom and stops at
the first match, so any ACCEPT rule inserted *below* the REJECT is dead code.
Insert above it:

```bash
# [VM]
sudo iptables -L INPUT -n --line-numbers          # find the REJECT line number
sudo iptables -I INPUT 5 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 5 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo iptables -L INPUT -n --line-numbers          # verify ACCEPTs are ABOVE the REJECT
sudo netfilter-persistent save                    # rules are in-memory only until saved
```

---

## 7. Debugging connectivity: test from the inside out

Never guess which layer is broken — narrow it down mechanically.

| Step | Command | If it fails, the problem is |
|---|---|---|
| 1 | `[VM] curl http://127.0.0.1:8081/actuator/health` | the application or its container |
| 2 | `[VM] curl -k https://localhost/actuator/health` | Nginx config or the upstream connection |
| 3 | `[LOCAL] curl -k https://<VM_PUBLIC_IP>/actuator/health` | a firewall layer (2, 3 or 4) |
| 4 | `[LOCAL] curl https://reconrail.in/actuator/health` | Cloudflare or the DNS record |

### Cloudflare error codes — each names a different failure

| Code | Meaning | Where to look |
|---|---|---|
| 502 (from Nginx) | Nginx is up but the upstream didn't answer | is the container running on 8081? |
| 521 | origin actively refused the connection | Nginx not running / not listening |
| 522 | connection to origin timed out | firewall dropping packets |
| 523 | origin is unreachable | firewall, or the DNS A record points at the wrong IP |
| 526 | origin certificate invalid | origin cert missing/wrong with Full (strict) |

---

## 8. Traps we hit (read before repeating this)

1. **`unknown directive "http2"`** — Ubuntu 24.04 ships nginx 1.24; the
   standalone `http2 on;` directive only exists from 1.25. Use
   `listen 443 ssl http2;` instead. The failed start also aborted the package
   install mid-configure; recover with `sudo dpkg --configure -a` after fixing.
2. **iptables rules inserted below the REJECT** — they never match. Verify
   ordering after every insert.
3. **Running server commands on the laptop** — WSL and the VM are different
   machines. Nginx got installed and configured in WSL by mistake, producing a
   confusing 502 while the VM had no Nginx at all. Always check the prompt.
4. **`./mvnw: Permission denied` in CI (exit 126)** — Windows doesn't store the
   Unix executable bit, so the Linux runner couldn't execute the wrapper. Fix:
   `git update-index --chmod=+x services/auth-service/mvnw` (verify with
   `git ls-files -s`, want mode `100755`).
5. **Image name mismatch** — `docker run` on a tag that doesn't exist locally
   silently attempts a registry pull and reports "pull access denied", which
   reads like a permissions problem but is really a typo.
6. **A Windows-installed PostgreSQL squatting on port 5432** locally, so the
   app authenticated against the wrong database while the Docker container sat
   idle. Diagnosed with `netstat -ano | findstr :5432` then
   `Get-Process -Id <PID>`.
7. **`ssh: handshake failed ... [none publickey]` in the deploy job** — the
      deploy key wasn't authorized on the server. Root cause was comical but
      instructive: the literal placeholder text `PASTE_THE_PUBLIC_KEY_LINE_HERE`
      had been appended to `authorized_keys` instead of the actual key. Diagnosed
      by testing the key manually first (`ssh -i ~/.ssh/reconrail_deploy ...`),
      which proved the failure was server-side rather than a GitHub secret
      formatting problem, then reading `cat -n ~/.ssh/authorized_keys`.
      *Lesson:* verify a credential works by hand before handing it to automation.