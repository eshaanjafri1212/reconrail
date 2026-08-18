package in.reconrail.auth.dto;

public record LoginApiResponse(String accessToken,
                            Long userId,
                            String tenantSlug,
                            String role
) {}