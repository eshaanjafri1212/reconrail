package in.reconrail.auth.service.impl;


import in.reconrail.auth.config.JwtProperties;
import in.reconrail.auth.entity.AppUser;
import in.reconrail.auth.entity.UserRole;
import in.reconrail.auth.security.AuthenticatedPrincipal;
import in.reconrail.auth.service.TokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TokenServiceImpl implements TokenService {
    private final JwtProperties props;
    private final PrivateKey privatekey;
    private final PublicKey publicKey;

    //thread-safe and expensive to seed
    private final SecureRandom secureRandom = new SecureRandom();
    public TokenServiceImpl(JwtProperties props, ResourceLoader resourceLoader) {
        this.props = props;
        this.privatekey = loadPrivateKey(resourceLoader, props.privateKey());
        this.publicKey  = loadPublicKey(resourceLoader, props.publicKey());
    }
    // ---------- key loading (runs once, at startup) ----------

    private byte[] decodePemBody(String pem, String type) {
        String body = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");          // strip newlines and spaces
        return Base64.getDecoder().decode(body);
    }

    private String readPem(ResourceLoader loader, String location) {
        try {
            Resource resource = loader.getResource(location);
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // Fail fast: an app that cannot load its signing keys must not start,
            // because it would silently accept or issue nothing verifiable.
            throw new IllegalStateException("Unable to read key file: " + location, e);
        }
    }

    private PrivateKey loadPrivateKey(ResourceLoader loader, String location) {
        try {
            byte[] der = decodePemBody(readPem(loader, location), "PRIVATE KEY");
            // PKCS#8 is the standard encoding for private keys.
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid RSA private key at " + location, e);
        }
    }

    private PublicKey loadPublicKey(ResourceLoader loader,String location){
        try{
            byte[] der = decodePemBody(readPem(loader,location),"PUBLIC KEY");
            // X.509 SubjectPublicKeyInfo is the standard encoding for public keys.
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        }
        catch(Exception e){
            throw new IllegalStateException("Invalid RSA public key at "+location,e);
        }
    }

    // ---------- access tokens ----------
    @Override
    public String issueAccessToken(AppUser user){
        Instant now = Instant.now();
        Instant expiry = now.plus(props.accessTokenTtl());

        Long tenantId = user.getTenant().getId();
        String tenantSlug = user.getTenant().getSlug();

        return Jwts.builder()
                .header().keyId(props.keyId()).and()
                .issuer(props.issuer())
                .subject(String.valueOf(user.getId()))
                .audience().add(props.audience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString())      // jti — enables future blocklisting
                .claim("tid", tenantId)
                .claim("tsl", tenantSlug)
                .claim("rol", user.getRole().name())
                // The ALGORITHM IS FIXED HERE, by us — never read from the token.
                // This is the defence against the classic "alg: none" / RS256→HS256 attacks.
                .signWith(privatekey, Jwts.SIG.RS256)
                .compact();

    }

    @Override
    public AuthenticatedPrincipal verifyAccessToken(String token) {
        // verifyWith(publicKey) pins both the key and, implicitly, asymmetric verification.
        // requireIssuer/requireAudience prevent a validly-signed token minted for a
        // different system from being accepted here.
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(props.issuer())
                .requireAudience(props.audience())
                .build()
                .parseSignedClaims(token);   // throws on bad signature, expiry, or claim mismatch

        Claims claims = jws.getPayload();

        return new AuthenticatedPrincipal(
                Long.valueOf(claims.getSubject()),
                claims.get("tid", Long.class),
                claims.get("tsl", String.class),
                null,                                        // email not carried as a claim
                UserRole.valueOf(claims.get("rol", String.class))
        );
    }

    @Override
    public String generateRefreshTokenValue() {
        // 32 bytes = 256 bits of entropy. SecureRandom, never java.util.Random,
        // whose output is predictable from a handful of samples.
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String hashRefreshToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            // 32 bytes → 64 hex chars, which is exactly the VARCHAR(64) column width.
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
