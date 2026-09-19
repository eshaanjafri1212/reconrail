# Runbook: Provisioning the ReconRail production server (OCI Always Free)

**Last verified:** 03 Aug 2026 · **Owner:** Eshaan Jafri
**Outcome:** A publicly reachable Ubuntu 24.04 ARM VM on Oracle Cloud running
Docker, fronted by Cloudflare DNS/TLS on `reconrail.in`.

---

## 0. Prerequisites

- Credit card with international + online transactions enabled (set temp
  limit ₹2,000–5,000; a ~$1 verification hold is charged and auto-reversed).
- A domain (ours: `reconrail.in` via Porkbun, ~$8/yr) + free Cloudflare account.
- An SSH key pair (generate before creating the VM):

```bash
  ssh-keygen -t ed25519 -C "reconrail-oci" -f ~/.ssh/reconrail_oci
```

`-t ed25519` = modern elliptic-curve algorithm; `-C` = label; `-f` = output
path. Produces `reconrail_oci` (PRIVATE — never leaves the laptop, never in
git) and `reconrail_oci.pub` (public — this is what the cloud provider gets).
Note: Git Bash and WSL have SEPARATE home dirs — the key must exist in
whichever shell you SSH from (copy + `chmod 600` if moving to WSL).

## 1. OCI account

1. oracle.com/cloud/free → Start for free. Real name matching the card.
2. **Home region is PERMANENT.** Chose India West (Mumbai). Choose closest to
   users; capacity struggles are region-wide, region-hopping isn't an option later.
3. Card verification (1.38 SGD hold), then wait for "account ready" email
   (minutes to hours).
4. Free tier reality (post-June-2026): Ampere A1 total of **2 OCPU / 12 GB**
   across the tenancy, 200 GB block storage, $300/30-day trial credit on top.

## 2. Create the VM

Compute → Instances → Create instance:

| Field | Value |
|---|---|
| Name | `reconrail-vm` |
| Image | Canonical Ubuntu 24.04 (aarch64 — pairs with ARM shape) |
| Shape | `VM.Standard.A1.Flex`, **2 OCPU / 12 GB** (Always Free-eligible) |
| Networking | Create new VCN + new **public** subnet, defaults (CIDR 10.0.0.0/24) |
| Public IPv4 | Toggle ON (it may refuse — see Traps) |
| SSH keys | Paste public keys → ONE clean `ssh-ed25519 ...` line |
| Boot volume | 100 GB custom |
| Shielded/Confidential | OFF |

**"Out of capacity for shape VM.Standard.A1.Flex"** = Mumbai's ARM hosts are
full. Not an account problem. Retry a few times, then retry **05:00–07:00 IST**
— worked first try for us. Escalation if blocked for days: upgrade to
Pay-As-You-Go (keeps Always Free at ₹0, gains capacity priority) + set a ₹100
budget alert immediately.

## 3. Make the subnet actually public (the trap that got us)

A "public" subnet needs THREE things: an **Internet Gateway** (the VCN's door
to the internet), a **route rule** `0.0.0.0/0 → IGW` (the road sign to that
door), and a public IP on the VNIC. Our VCN had the gateway but no route rule —
symptom: the public-IP toggle silently refuses / IP shows "(Not Assigned)".

Fix: Instance details → Quick actions → **"Connect public subnet to
internet"** → Create. This wires the route rule and attaches an NSG
(`ig-quick-action-NSG`).

Firewall layers (BOTH must allow traffic when an NSG is attached):
- Subnet **Security List**: ingress TCP 22 (default) + add TCP 80, 443 from
  `0.0.0.0/0`.
- **NSG** on the VNIC: add the same 80/443 ingress when deploying web traffic.

## 4. Attach the public IP

Instance → Attached VNICs → primary VNIC → IPv4 Addresses → ⋮ Edit on the
private IP row → Public IP type: **Ephemeral** (or attach an existing Reserved
IP) → Update. Ephemeral survives reboots; lost only on termination. Reserved
survives even termination — switch later if desired.

Record the IP. Ours: `130.210.49.228`.

## 5. First SSH login

```bash
ssh -i ~/.ssh/reconrail_oci ubuntu@130.210.49.228
```

- `-i` = identity (private key) file; `ubuntu` = default user on Ubuntu images.
- First-connect fingerprint prompt: this is YOU authenticating the SERVER
  (its host-key hash, recorded in `~/.ssh/known_hosts`). Type `yes` once at
  the prompt. If the fingerprint ever CHANGES for the same host later —
  investigate before connecting.
- OCI disables SSH password auth entirely; key-only. Bots will hammer port 22
  within minutes of the IP going live — normal internet background noise.

## 6. Server initialization

```bash
sudo apt update && sudo apt upgrade -y
```
Refresh package catalog, then install updates (`-y` auto-confirms). Accept
defaults on service-restart prompts.

## 7. Install Docker Engine (official repo, ARM64)

```bash
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker ubuntu
exit
```

What this does: trusts Docker's GPG signing key, registers Docker's apt repo
for THIS architecture (`dpkg --print-architecture` → arm64) and THIS Ubuntu
release (`$VERSION_CODENAME` → noble), installs engine + CLI + containerd +
buildx + compose plugin, and adds `ubuntu` to the `docker` group (so no sudo
needed). Group membership applies at login → must log out/in.

Verify after re-login:

```bash
docker run hello-world
```

## 8. DNS via Cloudflare

Cloudflare dashboard → zone `reconrail.in` → DNS → Records:

| Type | Name | Content | Proxy |
|---|---|---|---|
| A | `@` | 130.210.49.228 | **Proxied** (orange) |
| A | `www` | 130.210.49.228 | **Proxied** (orange) |

SSL/TLS mode: **Full** (upgrade to Full (strict) once origin has a real cert).

Verification: `ping reconrail.in` answers from a CLOUDFLARE address (ours
replied from IPv6 `2606:4700:...`), NOT the VM IP — proxy working, origin IP
hidden. Ports 80/443 serve nothing until Nginx is deployed (Phase 1); a
Cloudflare 521/522 error page is expected until then.

## Traps we hit (read before repeating this)

1. **₹100 card limit → declined.** Verification needs headroom; set ₹2–5k temp.
2. **Public-IP toggle silently refusing** at create time → missing route rule
   to the IGW. Quick action fixes it post-create.
3. **SSH key pasted twice** merged into one invalid line in the create form.
   Always eyeball the summary: exactly one `ssh-ed25519 ... <comment>` line.
4. **Out of capacity** → 5–7 AM IST retry window.
5. **WSL vs Git Bash**: key generated in one home doesn't exist in the other.
6. **Typing `yes` at a shell** (not at the prompt) runs the `yes` program —
   infinite `y`. Ctrl+C.