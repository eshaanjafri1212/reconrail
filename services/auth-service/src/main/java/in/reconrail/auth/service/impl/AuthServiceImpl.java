package in.reconrail.auth.service.impl;

import in.reconrail.auth.config.JwtProperties;
import in.reconrail.auth.constants.Constants;
import in.reconrail.auth.dto.AuthResponse;
import in.reconrail.auth.dto.LoginRequest;
import in.reconrail.auth.dto.LoginResponse;
import in.reconrail.auth.dto.RegisterRequest;
import in.reconrail.auth.entity.*;
import in.reconrail.auth.exception.DuplicateTenantException;
import in.reconrail.auth.exception.InvalidCredentialsException;
import in.reconrail.auth.repository.AppUserRepository;
import in.reconrail.auth.repository.RefreshTokenRepository;
import in.reconrail.auth.repository.TenantRepository;
import in.reconrail.auth.service.AuthService;
import in.reconrail.auth.service.TokenService;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;


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

    /**
     * Authenticates a user within a tenant and issues an access/refresh token pair.
     *
     * SECURITY CONTRACT: every failure path — unknown tenant, unknown user, wrong
     * password, disabled account — must produce an IDENTICAL response and take
     * comparable time. Any divergence lets an attacker enumerate valid accounts.
     */
    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String userAgent, String ipAddress){
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Optional<Tenant> tenantOpt = tenantRepository.findBySlug(request.tenantSlug());
        Optional<AppUser> userOpt = tenantOpt.flatMap(
                t -> userRepository.findByTenantIdAndEmail(t.getId() , email)
        );
        if(userOpt.isEmpty()){
            passwordEncoder.matches(request.password(), Constants.DUMMY_HASH);
            throw new InvalidCredentialsException();
        }
        AppUser user = userOpt.get();
        boolean passwordOk = passwordEncoder.matches(request.password(),user.getPassword());
        if(!passwordOk || !user.isEnabled()){
            throw new InvalidCredentialsException();
        }
        String accessToken = tokenService.issueAccessToken(user);
        String rawRefreshToken = tokenService.generateRefreshTokenValue();
        RefreshToken refreshToken = RefreshToken.builder()
                .userId(user.getId())
                .tenantId(user.getTenant().getId())
                .tokenHash(tokenService.hashRefreshToken(rawRefreshToken))
                .familyId(UUID.randomUUID())
                .expiresAt(OffsetDateTime.now().plus(jwtProperties.refreshTokenTtl()))
                .userAgent(truncate(userAgent,300))
                .ipAddress(truncate(ipAddress,45))
                .build();
        refreshTokenRepository.save(refreshToken);
        return new LoginResponse(
                accessToken,
                rawRefreshToken,
                user.getId(),
                user.getTenant().getSlug(),
                user.getRole().name()
        );
    }
    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

}
