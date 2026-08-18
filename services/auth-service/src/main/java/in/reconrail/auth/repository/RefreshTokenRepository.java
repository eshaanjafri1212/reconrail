package in.reconrail.auth.repository;

import in.reconrail.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken,Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
    int revokeFamily(UUID familyId, OffsetDateTime now);
}
