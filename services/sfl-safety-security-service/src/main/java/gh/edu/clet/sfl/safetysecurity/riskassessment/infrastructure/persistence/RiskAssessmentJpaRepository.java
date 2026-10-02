package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RiskAssessmentJpaRepository extends JpaRepository<RiskAssessmentJpaEntity, UUID>,
        JpaSpecificationExecutor<RiskAssessmentJpaEntity> {

    List<RiskAssessmentJpaEntity> findBySiteCodeOrderByReferenceAsc(String siteCode);

    List<RiskAssessmentJpaEntity> findBySiteCodeAndCurrentVersionIsNotNull(String siteCode);

    List<RiskAssessmentJpaEntity> findBySiteCodeAndActivityTypeAndCurrentVersionIsNotNull(String siteCode,
            String activityType);
}
