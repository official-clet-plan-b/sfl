package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlagStatus;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewTrigger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewFlagJpaRepository extends JpaRepository<ReviewFlagJpaEntity, UUID> {

    Optional<ReviewFlagJpaEntity> findByAssessmentIdAndTriggerTypeAndSourceId(UUID assessmentId,
            ReviewTrigger triggerType, String sourceId);

    List<ReviewFlagJpaEntity> findByAssessmentIdOrderByRaisedAtDesc(UUID assessmentId);

    Page<ReviewFlagJpaEntity> findBySiteCode(String siteCode, Pageable pageable);

    Page<ReviewFlagJpaEntity> findBySiteCodeAndStatus(String siteCode, ReviewFlagStatus status, Pageable pageable);

    long countBySiteCodeAndStatus(String siteCode, ReviewFlagStatus status);

    List<ReviewFlagJpaEntity> findByStatusAndDeferredUntilLessThanEqual(ReviewFlagStatus status, Instant now);
}
