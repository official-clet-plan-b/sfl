package gh.edu.clet.sfl.safetysecurity.permit.infrastructure.integration;

import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRiskAssessmentPort;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitRiskLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.RiskAssessmentStanding;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.contract.RiskAssessmentDirectory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one S164 class that knows S165 exists - SRS-SFL-S164-01 "a request cannot be submitted without a linked, in-date Risk Assessment
 * Library (S165) record". In-process, through S165's published {@link RiskAssessmentDirectory}; the verdict is S165's own, by the
 * shared currency rule, so the two systems cannot disagree about whether an assessment is current.
 */
@Component
public class RiskAssessmentDirectoryAdapter implements PermitRiskAssessmentPort {

    private final RiskAssessmentDirectory directory;

    public RiskAssessmentDirectoryAdapter(RiskAssessmentDirectory directory) {
        this.directory = directory;
    }

    @Override
    public RiskAssessmentStanding standing(UUID assessmentId, String siteCode, Instant at) {
        RiskAssessmentDirectory.LinkCheck check = directory.checkLink(assessmentId, siteCode, at);
        return new RiskAssessmentStanding(check.assessmentId(), check.found(), check.reference(), check.version(), check.activityType(),
                check.riskLevel() == null ? null : PermitRiskLevel.valueOf(check.riskLevel().name()), check.reviewDueAt(), check.linkable(),
                check.verdict() == null || check.verdict().reason() == null ? null : check.verdict().reason().name());
    }
}
