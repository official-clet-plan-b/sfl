package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentTemplateJpaRepository extends JpaRepository<AssessmentTemplateJpaEntity, UUID> {

    List<AssessmentTemplateJpaEntity> findByActiveTrueOrderByNameAsc();

    List<AssessmentTemplateJpaEntity> findAllByOrderByNameAsc();
}
