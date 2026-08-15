package in.reconrail.auth.service;

import in.reconrail.auth.entity.AppUser;
import org.springframework.security.core.AuthenticatedPrincipal;

public interface TokenService {
    /** Issues a signed, short-lived access token carrying identity and tenant claims. */
    String issueAccessToken(AppUser user);
    /** Generates a cryptographically random refresh token (the raw value, returned to the client once). */
    String generateRefreshTokenValue();
    /** Hashes a raw refresh token for storage — the database never holds the usable value. */
    String hasRefreshToken(String rawToken);
    /** Parses and verifies an access token, returning its claims. Throws if invalid or expired. */
    AuthenticatedPrincipal verifyAccessToken(String token);
}
