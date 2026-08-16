package in.reconrail.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "reconrail.jwt")
public record JwtProperties(String issuer,
                            String audience,
                            Duration accessTokenTtl,
                            Duration refreshTokenTtl,
                            String privateKey,
                            String publicKey,
                            String keyId
) {}