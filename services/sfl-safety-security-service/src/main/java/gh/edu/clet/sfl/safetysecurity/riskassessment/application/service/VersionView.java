package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.Status;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlMeasure;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.HazardType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Likelihood;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Severity;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/**
 * One version as a reader sees it: the record, plus what the service computes from it - the risk level,
 * every hazard's scores and bands, and the currency verdict - so no client re-derives the risk matrix and
 * presents its own answer as the service's.
 *
 * @param currencyReason the currency rule's reason this version is not current, or null when it is; null
 *        for a draft, which has no review date to judge
 */
public record VersionView(AssessmentVersion version, RiskLevel riskLevel, int residualScore,
        List<HazardView> hazards, boolean current, RiskAssessmentCurrency.Reason currencyReason) {

    /** One hazard with both ratings scored and banded, numbered from 1 as its author numbers it. */
    public record HazardView(int number, HazardType hazardType, String description, String whoAtRisk,
            Likelihood inherentLikelihood, Severity inherentSeverity, int inherentScore, RiskLevel inherentLevel,
            Likelihood residualLikelihood, Severity residualSeverity, int residualScore, RiskLevel residualLevel,
            List<ControlMeasure> controls) {

        static HazardView of(int number, Hazard hazard) {
            return new HazardView(number, hazard.hazardType(), hazard.description(), hazard.whoAtRisk(),
                    hazard.inherentRisk().likelihood(), hazard.inherentRisk().severity(), hazard.inherentRisk().score(),
                    hazard.inherentRisk().level(), hazard.residualRisk().likelihood(),
                    hazard.residualRisk().severity(), hazard.residualRisk().score(), hazard.residualRisk().level(),
                    hazard.controls());
        }
    }

    public static VersionView of(AssessmentVersion version, Instant now) {
        List<Hazard> hazards = version.content().hazards();
        boolean judged = version.status() != Status.DRAFT;
        RiskAssessmentCurrency.Verdict verdict = judged ? version.currency(now) : null;
        return new VersionView(version, version.riskLevel(), version.content().residualScore(),
                IntStream.range(0, hazards.size()).mapToObj(i -> HazardView.of(i + 1, hazards.get(i))).toList(),
                verdict != null && verdict.current(), verdict == null ? null : verdict.reason());
    }
}
