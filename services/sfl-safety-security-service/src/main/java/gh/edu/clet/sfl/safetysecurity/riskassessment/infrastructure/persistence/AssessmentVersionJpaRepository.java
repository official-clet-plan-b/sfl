package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.Status;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentVersionJpaRepository extends JpaRepository<AssessmentVersionJpaEntity, UUID> {

    Optional<AssessmentVersionJpaEntity> findByAssessmentIdAndVersionNumber(UUID assessmentId, int versionNumber);

    List<AssessmentVersionJpaEntity> findByAssessmentIdOrderByVersionNumberDesc(UUID assessmentId);

    List<AssessmentVersionJpaEntity> findBySiteCodeAndStatus(String siteCode, Status status);

    List<AssessmentVersionJpaEntity> findByStatusAndReviewDueAtLessThanEqual(Status status, Instant cutoff);
}
