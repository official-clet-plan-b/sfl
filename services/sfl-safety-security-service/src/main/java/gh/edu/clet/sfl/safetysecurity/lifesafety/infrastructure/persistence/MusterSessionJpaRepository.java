package gh.edu.clet.sfl.safetysecurity.lifesafety.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MusterSessionJpaRepository extends JpaRepository<MusterSessionJpaEntity, UUID> {

    Optional<MusterSessionJpaEntity> findFirstBySiteCodeAndZoneCodeAndStatusAndDrillIdIsNull(String siteCode,
            String zoneCode, MusterStatus status);
}
