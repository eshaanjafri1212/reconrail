package in.reconrail.auth.repository;

import in.reconrail.auth.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByTenantIdAndEmail(Long tenantId, String email);
    boolean existsByTenantIdAndEmail(Long tenantId, String email);
}