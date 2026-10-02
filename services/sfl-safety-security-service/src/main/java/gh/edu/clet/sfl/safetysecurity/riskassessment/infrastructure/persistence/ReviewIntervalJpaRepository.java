package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewIntervalJpaRepository extends JpaRepository<ReviewIntervalJpaEntity, RiskLevel> {
}
