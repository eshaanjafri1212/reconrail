package in.reconrail.auth.dto;

public record LoginResponse(String accessToken,
                            String refreshToken,     // controller puts this in an httpOnly cookie
                            Long userId,
                            String tenantSlug,
                            String role
) {}