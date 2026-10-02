package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObservedActivityTypeJpaRepository extends JpaRepository<ObservedActivityTypeJpaEntity, UUID> {

    Optional<ObservedActivityTypeJpaEntity> findBySiteCodeAndActivityTypeAndSourceSystem(String siteCode,
            String activityType, String sourceSystem);

    List<ObservedActivityTypeJpaEntity> findBySiteCode(String siteCode);
}
