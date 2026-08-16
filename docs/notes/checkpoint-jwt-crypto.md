# Checkpoint notes: JWT & cryptography (Phase 1, auth block)

**Date:** 16 Aug 2026
**Covers:** `TokenServiceImpl`, RSA key handling, refresh token generation.
**Related:** ADR-0004 (authentication architecture), ADR-0003 (multi-tenancy).

---

## 1. Signed is not encrypted

A JWT is three base64url segments joined by dots: `header.payload.signature`.

- **Header** — `{"alg":"RS256","typ":"JWT","kid":"reconrail-key-1"}`. The `kid`
  identifies which key signed it, which is what makes key rotation possible
  without a synchronised cutover across every service.
- **Payload** — the claims. **Readable by anyone holding the token.** Paste any
  JWT into a decoder and the claims appear in plaintext. Base64 is an encoding,
  not encryption.
- **Signature** — the private key signing `base64(header) + "." + base64(payload)`.
  Alter one character of the payload and verification fails.

**The practical rule:** never put anything in a claim that would be damaging to
disclose. This is why email was removed from our claim set — it was convenient
but unnecessary for an authorization decision, and a leaked token would have
disclosed it.

## 2. The `alg` attacks — why the algorithm is fixed in code

Two historical vulnerability classes, both worth being able to describe:

**`alg: none`.** Early JWT libraries read the algorithm from the token header
and trusted it. An attacker sets `"alg":"none"`, strips the signature entirely,
and the library accepts the token as valid — because the token told it not to
check.

**RS256 → HS256 confusion.** With asymmetric signing the public key is, by
design, public. An attacker changes the header to `HS256` (symmetric) and signs
the forged token using the **public key as the HMAC secret**. A naive library
reads `alg: HS256`, fetches "the key", and verifies successfully — because for
HMAC the same key signs and verifies.

**Our defence:** the verifier decides the algorithm, never the token.

```java
.signWith(privateKey, Jwts.SIG.RS256)      // issuing: algorithm fixed by us
.verifyWith(publicKey)                      // verifying: key type pins the algorithm
```

## 3. Why `requireIssuer` and `requireAudience`

Without them, **any** token bearing a valid signature from a trusted key is
accepted — including one minted for a different system that happens to share
key material or infrastructure. These two checks scope a token to *this*
system and *this* API. They cost nothing and close a whole class of
token-confusion attacks.

```java
Jwts.parser()
    .verifyWith(publicKey)
    .requireIssuer(props.issuer())      // must come from reconrail-auth
    .requireAudience(props.audience())  // must be intended for reconrail-api
```

## 4. SHA-256 for refresh tokens, BCrypt for passwords — different threats

| | Password | Refresh token |
|---|---|---|
| Entropy | low — human-chosen, often reused | 256 bits of `SecureRandom` |
| Threat | offline brute force / dictionary attack | database leak yielding usable credentials |
| Hash | **BCrypt**, work factor 10 | **SHA-256** |
| Why | deliberate slowness makes billions of guesses infeasible | nothing to guess; slowness would only add latency to every refresh |

The general principle: **choose the hash to match the threat model**, not by
reputation. Using BCrypt everywhere sounds safer and is simply wasteful here;
using SHA-256 for passwords would be a serious vulnerability.

Both cases share one rule: the database never stores the usable value. A
refresh token is stored as `SHA-256(token)` exactly as a password is stored as
`bcrypt(password)`, so a database dump alone does not hand the attacker working
credentials.

## 5. `SecureRandom`, never `Random`

`java.util.Random` is a linear congruential generator. Given a handful of
outputs, its internal state can be reconstructed and all future values
predicted. For a refresh token — a bearer credential valid for 30 days — that
would let an attacker generate valid tokens directly.

`SecureRandom` draws from the operating system's cryptographic entropy source.
It is thread-safe and expensive to seed, so it is created once as a field and
reused rather than instantiated per call.

## 6. Keys are loaded once, at startup, and failure is fatal

PEM parsing happens in the constructor, not per request — parsing a key on
every token issue would be a genuine performance defect.

Failures throw `IllegalStateException`, which prevents the application from
starting. This is deliberate: an authentication service that cannot load its
signing keys must **fail closed**. Starting up in a degraded state and
rejecting (or worse, accepting) everything is far more dangerous than not
starting at all.

The two encodings are not interchangeable: private keys use **PKCS#8**
(`PKCS8EncodedKeySpec`), public keys use **X.509 SubjectPublicKeyInfo**
(`X509EncodedKeySpec`).

## 7. The lazy-loading hazard

`user.getTenant()` is a `FetchType.LAZY` association, so `issueAccessToken`
throws `LazyInitializationException` if called outside an active persistence
context. It must be invoked from within the `@Transactional` login method.

The alternative design — passing the tenant id and slug as explicit method
parameters — is more honest about the dependency and worth considering if the
call sites multiply.

---

## Interview questions from this block

### "Is a JWT encrypted?"

> No — it's signed. The payload is base64-encoded and readable by anyone
> holding the token. Signing guarantees integrity and authenticity, not
> confidentiality. That's why I keep the claim set minimal and excluded the
> user's email: it wasn't needed for an authorization decision, and every claim
> is data disclosed to whoever obtains the token. If confidentiality of claims
> were required, that's JWE rather than JWS.

### "What's the `alg: none` attack?"

> Early JWT libraries trusted the algorithm declared in the token's own header.
> An attacker sets it to `none`, removes the signature, and the library accepts
> the token because the token told it not to verify. The related variant flips
> RS256 to HS256 and signs with the public key as the HMAC secret — since the
> public key is public by design, anyone can forge that. The defence in both
> cases is that the verifier must fix the algorithm in code and never read it
> from the token.

### "Why hash refresh tokens with SHA-256 instead of BCrypt?"

> Because the threat is different. BCrypt is deliberately slow to defeat
> brute-force attacks on low-entropy, human-chosen passwords. A refresh token
> is 256 bits from `SecureRandom` — there is nothing to brute force, so
> BCrypt's cost factor would add latency to every refresh and buy no security.
> What both share is that the database stores a hash rather than the usable
> value, so a database leak doesn't yield working credentials.

### "How would you rotate the signing key without downtime?"

> The `kid` header claim makes it possible. You publish both the old and new
> public keys through the JWKS endpoint, start signing new tokens with the new
> key while verifiers select by `kid`, and once all tokens signed with the old
> key have expired — bounded by the 15-minute access token lifetime — retire
> it. Without `kid` you'd need a flag-day cutover across every service, which
> is exactly the kind of coordinated deployment I designed this to avoid.

### "Where do you store the private key?"

> Never in the repository. Locally it's a PEM file under a gitignored directory
> — verified with `git check-ignore` rather than assumed. In production it's
> supplied through an environment variable pointing at a mounted secret, using
> the same `${VAR:default}` pattern as the database password, so the identical
> container image runs in both environments with different configuration.
> Dev and production use separate key pairs; the development key is never
> promoted.