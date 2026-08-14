package in.reconrail.auth.service.impl;

import in.reconrail.auth.dto.AuthResponse;
import in.reconrail.auth.dto.RegisterRequest;
import in.reconrail.auth.entity.AppUser;
import in.reconrail.auth.entity.Tenant;
import in.reconrail.auth.entity.TenantStatus;
import in.reconrail.auth.entity.UserRole;
import in.reconrail.auth.exception.DuplicateTenantException;
import in.reconrail.auth.repository.AppUserRepository;
import in.reconrail.auth.repository.TenantRepository;
import in.reconrail.auth.service.AuthService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request){
        String slug = toSlug(request.companyName());

        if(tenantRepository.existsBySlug(slug)){
            throw new DuplicateTenantException("A workspace with a similiar name already exists");
        }
        Tenant tenant = tenantRepository.save(
                Tenant.builder()
                        .name(request.companyName())
                        .slug(slug)
                        .status(TenantStatus.ACTIVE)
                        .build());

        String email = request.email().trim().toLowerCase(Locale.ROOT);

        AppUser user = userRepository.save(
                AppUser.builder()
                        .tenant(tenant)
                        .email(email)
                        .password(passwordEncoder.encode(request.password()))
                        .fullName(request.fullName())
                        .role(UserRole.TENANT_ADMIN)
                        .enabled(true)
                        .build());
        return new AuthResponse(user.getId(), user.getEmail(),
                tenant.getSlug(), user.getRole().name());
    }


    private String toSlug(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

}
