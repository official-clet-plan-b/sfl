package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignOffJpaRepository extends JpaRepository<SignOffJpaEntity, UUID> {

    List<SignOffJpaEntity> findByAssessmentIdOrderBySignedOffAtDesc(UUID assessmentId);
}
