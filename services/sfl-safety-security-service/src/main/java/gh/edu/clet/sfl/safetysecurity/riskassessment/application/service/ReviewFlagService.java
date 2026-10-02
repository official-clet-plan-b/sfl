package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentPage;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ActivityTypes;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlagStatus;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewTrigger;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S165-04: an incident flags the assessment it happened under for out-of-cycle review, and only a
 * completed review with findings clears it.
 *
 * <p>Workflow, in the SRS's words: "Incident logged in S163 -> linked risk assessment identified ->
 * flagged for out-of-cycle review -> review completed with findings -> flag cleared." There is no dismiss
 * - not as an endpoint, not as a status.
 */
@Service
public class ReviewFlagService {

    /** The SRS system an incident comes from, as the coverage feed records it. */
    static final String INCIDENT_SOURCE = "S163";

    private final RiskAssessmentRepository repository;
    private final RiskAssessmentAccessPolicy access;
    private final CoverageService coverage;
    private final RiskAssessmentEvents events;
    private final AuditPort audit;
    private final Clock clock;

    public ReviewFlagService(RiskAssessmentRepository repository, RiskAssessmentAccessPolicy access,
            CoverageService coverage, RiskAssessmentEvents events, AuditPort audit, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.coverage = coverage;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * What an incident says about risk: the assessment it was linked to, or the activity it happened
     * during, or both.
     */
    public record IncidentRiskContext(UUID incidentId, String incidentReference, String siteCode,
            UUID riskAssessmentId, String activityType, ActorContext actor) {
    }

    /**
     * Flags every assessment the incident identifies - S165-04 "flagged for review within the same
     * operational day", met by flagging in the same transaction as the incident save.
     *
     * <p>An explicitly linked assessment is flagged if anything of it has been published, current or
     * lapsed: a lapsed assessment under which something went wrong needs the review more, not less. An
     * activity type flags every published assessment at the site scoped to that activity. A draft that was
     * never published is not flagged - nobody relied on it.
     *
     * <p>Not permission-checked against the S165 matrix: whoever reported the incident is not asking S165
     * for anything, the platform is reacting to their report. Idempotent per incident and assessment, so
     * the same incident saved twice raises one flag.
     *
     * @throws RiskAssessmentException {@code RISK_ASSESSMENT_LINK_NOT_FOUND} when an explicit link names no
     *     assessment at the incident's site - the incident must not save with a link to nothing
     */
    @Transactional
    public List<ReviewFlag> flagFromIncident(IncidentRiskContext context) {
        String site = context.siteCode().strip().toUpperCase(Locale.ROOT);
        String activityType = ActivityTypes.normalise(context.activityType());
        List<RiskAssessment> targets = new ArrayList<>();
        if (context.riskAssessmentId() != null) {
            RiskAssessment linked = repository.findAssessment(context.riskAssessmentId())
                    .filter(assessment -> assessment.siteCode().equals(site))
                    .orElseThrow(() -> new RiskAssessmentException(
                            RiskAssessmentErrorCode.RISK_ASSESSMENT_LINK_NOT_FOUND,
                            Map.of("assessmentId", context.riskAssessmentId().toString(), "siteCode", site)));
            targets.add(linked);
        }
        if (activityType != null) {
            coverage.observe(site, activityType, INCIDENT_SOURCE, context.incidentReference(), context.actor());
            repository.findPublishedByActivityType(site, activityType).stream()
                    .filter(assessment -> targets.stream().noneMatch(t -> t.id().equals(assessment.id())))
                    .forEach(targets::add);
        }

        Instant now = clock.instant();
        List<ReviewFlag> raised = new ArrayList<>();
        for (RiskAssessment assessment : targets) {
            if (assessment.currentVersion() == null) {
                continue;
            }
            String sourceId = context.incidentId().toString();
            if (repository.findFlag(assessment.id(), ReviewTrigger.INCIDENT, sourceId).isPresent()) {
                continue;
            }
            ReviewFlag flag = repository.saveFlag(ReviewFlag.raise(UUID.randomUUID(), assessment,
                    assessment.currentVersion(), ReviewTrigger.INCIDENT, sourceId, context.incidentReference(),
                    "Incident " + context.incidentReference() + " occurred under this assessment.",
                    context.actor().actorId(), now, SourceChannel.SYSTEM, context.actor().correlationId()));
            audit.record(context.actor(), SourceChannel.SYSTEM.name(), flag.siteCode(),
                    "RISK_ASSESSMENT_REVIEW_FLAGGED", "ReviewFlag", flag.id().toString(), null, flag, null);
            events.reviewFlagged(flag, context.actor());
            raised.add(flag);
        }
        return raised;
    }

    /** S165-04: "it can be deferred with a named reason and date, but not silently cleared." */
    @Transactional
    public ReviewFlag defer(UUID flagId, String reason, LocalDate until, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        ReviewFlag flag = requireFlag(flagId);
        access.require(actor, SflPermission.RISK_ASSESSMENT_REVIEW_FLAG_MANAGE, flag.siteCode(), "ReviewFlag",
                flagId.toString());
        flag.metadata().requireVersion(expectedVersion);
        Instant now = clock.instant();
        Instant untilInstant = until == null ? null : until.atStartOfDay(ZoneOffset.UTC).toInstant();
        ReviewFlag deferred = repository.saveFlag(flag.defer(reason, untilInstant, actor.actorId(), now, channel,
                actor.correlationId()));
        audit.record(actor, channel.name(), flag.siteCode(), "RISK_ASSESSMENT_REVIEW_FLAG_DEFERRED", "ReviewFlag",
                flagId.toString(), flag, deferred, reason);
        return deferred;
    }

    /** S165-04: "The flag is cleared only by a completed review with recorded findings." */
    @Transactional
    public ReviewFlag complete(UUID flagId, String findings, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        ReviewFlag flag = requireFlag(flagId);
        access.require(actor, SflPermission.RISK_ASSESSMENT_REVIEW_FLAG_MANAGE, flag.siteCode(), "ReviewFlag",
                flagId.toString());
        flag.metadata().requireVersion(expectedVersion);
        ReviewFlag cleared = repository.saveFlag(flag.complete(findings, actor.actorId(), clock.instant(), channel,
                actor.correlationId()));
        audit.record(actor, channel.name(), flag.siteCode(), "RISK_ASSESSMENT_REVIEW_FLAG_CLEARED", "ReviewFlag",
                flagId.toString(), flag, cleared, findings);
        return cleared;
    }

    @Transactional(readOnly = true)
    public ReviewFlag get(UUID flagId, ActorContext actor) {
        ReviewFlag flag = requireFlag(flagId);
        access.require(actor, SflPermission.RISK_ASSESSMENT_READ, flag.siteCode(), "ReviewFlag", flagId.toString());
        return flag;
    }

    @Transactional(readOnly = true)
    public RiskAssessmentPage<ReviewFlag> search(String siteCode, ReviewFlagStatus status, Paging paging,
            ActorContext actor) {
        access.require(actor, SflPermission.RISK_ASSESSMENT_READ, siteCode, "ReviewFlag", null);
        return repository.searchFlags(siteCode.strip().toUpperCase(Locale.ROOT), status, paging);
    }

    /** Deferred flags whose date has passed, across sites - for the sweep. */
    @Transactional(readOnly = true)
    public List<UUID> expiredDeferrals() {
        return repository.findExpiredDeferrals(clock.instant()).stream().map(ReviewFlag::id).toList();
    }

    /** A deferral is a postponement, not a dismissal: when its date passes the flag is open again. */
    @Transactional
    public void reopenExpiredDeferral(UUID flagId, ActorContext system) {
        ReviewFlag flag = repository.findFlag(flagId).orElse(null);
        Instant now = clock.instant();
        if (flag == null || !flag.deferralExpired(now)) {
            return;
        }
        ReviewFlag reopened = repository.saveFlag(flag.reopen(system.actorId(), now, system.correlationId()));
        audit.record(system, SourceChannel.SYSTEM.name(), flag.siteCode(), "RISK_ASSESSMENT_REVIEW_FLAG_REOPENED",
                "ReviewFlag", flagId.toString(), flag, reopened, "Deferral date passed");
    }

    private ReviewFlag requireFlag(UUID flagId) {
        return repository.findFlag(flagId).orElseThrow(() -> RiskAssessmentException.notFound("ReviewFlag", flagId));
    }
}
