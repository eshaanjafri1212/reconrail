package in.reconrail.auth.security;

import in.reconrail.auth.entity.UserRole;

public record AuthenticatedPrincipal(Long userId,
                                     Long tenantId,
                                     String tenantSlug,
                                     UserRole role
) {}