package in.reconrail.auth.dto;

public record AuthResponse(
        Long userId, String email, String tenantSlug, String role
) {}