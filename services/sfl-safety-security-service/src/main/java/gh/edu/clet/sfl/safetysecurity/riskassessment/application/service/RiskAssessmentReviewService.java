package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.contract.RiskAssessmentDirectory;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewInterval;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SignOff;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.ReviewSchedulePolicy;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.SignOffPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S165-02: review sign-off, the reminder ahead of the due date, the lapse when it passes, and the
 * check every consumer makes before linking.
 *
 * <p>Workflow, in the SRS's words: "Assessment published with review interval -> reminder raised ahead of
 * due date -> reviewed and re-signed, or lapses to not-current -> downstream consumers blocked from
 * linking a lapsed assessment."
 *
 * <p>The lapse itself needs no sweep to take effect: the currency rule reads the review date, so an
 * assessment is not current from the instant its date passes, here and in every consumer's projection.
 * The sweep exists to <em>say so</em> - to record the moment and publish {@code review-lapsed} once - and
 * to send the reminder.
 */
@Service
public class RiskAssessmentReviewService implements RiskAssessmentDirectory {

    private final RiskAssessmentRepository repository;
    private final RiskAssessmentAccessPolicy access;
    private final ReviewIntervalService intervals;
    private final RiskAssessmentEvents events;
    private final AuditPort audit;
    private final Clock clock;

    public RiskAssessmentReviewService(RiskAssessmentRepository repository, RiskAssessmentAccessPolicy access,
            ReviewIntervalService intervals, RiskAssessmentEvents events, AuditPort audit, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.intervals = intervals;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Signs off the current version and renews its review date. For HIGH and CRITICAL the reviewer must not
     * be the version's author - refused with S165-02's own wording otherwise.
     */
    @Transactional
    public AssessmentDetailSignOff signOff(UUID assessmentId, String notes, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        RiskAssessment assessment = repository.findAssessment(assessmentId)
                .orElseThrow(() -> RiskAssessmentException.notFound("RiskAssessment", assessmentId));
        access.require(actor, SflPermission.RISK_ASSESSMENT_SIGN_OFF, assessment.siteCode(), "RiskAssessment",
                assessmentId.toString());
        if (assessment.currentVersion() == null) {
            throw RiskAssessmentException.invalidState("Nothing has been published to sign off.");
        }
        AssessmentVersion current = repository.findVersion(assessmentId, assessment.currentVersion())
                .orElseThrow(() -> RiskAssessmentException.notFound("AssessmentVersion", assessmentId));
        current.metadata().requireVersion(expectedVersion);
        int intervalDays = intervals.intervalFor(current.riskLevel()).intervalDays();

        Instant now = clock.instant();
        AssessmentVersion signed = repository.saveVersion(current.signOff(actor.actorId(),
                actor.principal().displayName(), intervalDays, now, channel, actor.correlationId()));
        SignOff record = repository.saveSignOff(new SignOff(UUID.randomUUID(), assessmentId, signed.id(),
                signed.versionNumber(), signed.siteCode(), actor.actorId(), actor.principal().displayName(), now,
                notes, current.reviewDueAt(), signed.reviewDueAt(),
                SignOffPolicy.independent(signed.authorId(), actor.actorId()),
                RecordMetadata.createdBy(actor.actorId(), now, channel, actor.correlationId())));
        RiskAssessment summarised = repository.saveAssessment(assessment.summarise(signed, actor.actorId(), now,
                channel, actor.correlationId()));
        audit.record(actor, channel.name(), assessment.siteCode(), "RISK_ASSESSMENT_SIGNED_OFF", "RiskAssessment",
                assessmentId.toString(), current, signed, notes);
        events.signedOff(signed, actor);
        return new AssessmentDetailSignOff(AssessmentSummary.of(summarised, now), record);
    }

    /** The sign-off just recorded, and the assessment as it now stands. */
    public record AssessmentDetailSignOff(AssessmentSummary assessment, SignOff signOff) {
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not permission-checked: the caller is another module acting for its own user, and has already
     * authorised them for the record they are linking from. What this answers is a fact about S165, not
     * something the S165 matrix grants.
     */
    @Override
    @Transactional(readOnly = true)
    public LinkCheck checkLink(UUID assessmentId, String siteCode, Instant at) {
        RiskAssessment assessment = repository.findAssessment(assessmentId).orElse(null);
        String site = siteCode == null ? null : siteCode.strip().toUpperCase(Locale.ROOT);
        if (assessment == null || !assessment.siteCode().equals(site)) {
            return new LinkCheck(assessmentId, false, null, null, null, null, null, null,
                    RiskAssessmentCurrency.assess(null, at));
        }
        return new LinkCheck(assessmentId, true, assessment.reference(), assessment.currentVersion(),
                assessment.activityType(), assessment.locationCode(), assessment.currentRiskLevel(),
                assessment.currentReviewDueAt(), assessment.currency(at));
    }

    /** The HTTP face of {@link #checkLink}: permission-checked, for an operator asking the same question. */
    @Transactional(readOnly = true)
    public LinkCheck checkLink(UUID assessmentId, String siteCode, ActorContext actor) {
        access.require(actor, SflPermission.RISK_ASSESSMENT_READ, siteCode, "RiskAssessment", assessmentId.toString());
        return checkLink(assessmentId, siteCode, clock.instant());
    }

    /**
     * For a caller that refuses rather than reports: throws with S165-02's "Lapsed Assessment" wording when
     * the reason is the review date, or the general not-current refusal otherwise.
     */
    public static void requireLinkable(LinkCheck check) {
        if (!check.found()) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_LINK_NOT_FOUND,
                    Map.of("assessmentId", String.valueOf(check.assessmentId())));
        }
        if (check.verdict().current()) {
            return;
        }
        if (check.verdict().reason() == RiskAssessmentCurrency.Reason.REVIEW_LAPSED) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_LAPSED,
                    Map.of("assessmentId", check.assessmentId().toString(), "reviewDueAt",
                            String.valueOf(check.reviewDueAt())));
        }
        throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_NOT_CURRENT,
                Map.of("assessmentId", check.assessmentId().toString(), "reason", check.verdict().reason().name()));
    }

    // ---- the sweep ---------------------------------------------------------------------------------

    /** A version the sweep should look at, by key - each is then processed in its own transaction. */
    public record SweepCandidate(UUID assessmentId, int versionNumber) {
    }

    /** Every published version whose reminder window has opened or whose date has passed, across sites. */
    @Transactional(readOnly = true)
    public List<SweepCandidate> sweepCandidates() {
        Instant cutoff = clock.instant().plus(Duration.ofDays(intervals.longestReminderLeadDays()));
        return repository.findPublishedVersionsDueBy(cutoff).stream()
                .filter(v -> v.reviewLapsedAt() == null)
                .map(v -> new SweepCandidate(v.assessmentId(), v.versionNumber()))
                .toList();
    }

    /** What the sweep did to one version. */
    public enum SweepOutcome { LAPSED, REMINDED, NOTHING }

    /**
     * Records a lapse or sends a reminder for one version, at most once each: a lapse is recorded when the
     * date has passed and has not been recorded; a reminder when the window is open and none has been sent
     * for the current due date (a sign-off clears both, so the next cycle reminds again).
     */
    @Transactional
    public SweepOutcome sweep(SweepCandidate candidate, ActorContext system) {
        AssessmentVersion version = repository.findVersion(candidate.assessmentId(), candidate.versionNumber())
                .orElse(null);
        if (version == null || !version.isPublished()) {
            return SweepOutcome.NOTHING;
        }
        RiskAssessment assessment = repository.findAssessment(version.assessmentId()).orElseThrow();
        Instant now = clock.instant();
        if (ReviewSchedulePolicy.lapsed(version.reviewDueAt(), now) && version.reviewLapsedAt() == null) {
            AssessmentVersion recorded = repository.saveVersion(version.lapseRecorded(system.actorId(), now,
                    system.correlationId()));
            audit.record(system, SourceChannel.SYSTEM.name(), version.siteCode(), "RISK_ASSESSMENT_REVIEW_LAPSED",
                    "RiskAssessment", version.assessmentId().toString(), version, recorded, null);
            events.reviewLapsed(recorded, system);
            return SweepOutcome.LAPSED;
        }
        ReviewInterval interval = intervals.intervalFor(version.riskLevel());
        if (version.reviewReminderSentAt() == null
                && ReviewSchedulePolicy.reminderDue(version.reviewDueAt(), interval.reminderLeadDays(), now)) {
            AssessmentVersion reminded = repository.saveVersion(version.reminderSent(system.actorId(), now,
                    system.correlationId()));
            audit.record(system, SourceChannel.SYSTEM.name(), version.siteCode(), "RISK_ASSESSMENT_REVIEW_DUE",
                    "RiskAssessment", version.assessmentId().toString(), null, reminded, null);
            events.reviewDue(assessment, reminded, system);
            return SweepOutcome.REMINDED;
        }
        return SweepOutcome.NOTHING;
    }
}
