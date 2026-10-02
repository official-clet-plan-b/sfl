package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentPage;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository.AssessmentQuery;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository.StandingFilter;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentContent;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentTemplate;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SignOff;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.PublishPolicy;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S165-01: author against a template, edit the draft, publish it as the current version, and open
 * a revision - with every superseded version kept and readable.
 *
 * <p>Workflow, in the SRS's words: "Officer drafts assessment -> hazards/controls/residual risk recorded ->
 * reviewed -> published as current version -> prior version retained, marked superseded." The "reviewed"
 * step for a higher-risk assessment is the independent sign-off in {@link RiskAssessmentReviewService};
 * publishing does not wait for it, but the currency rule does - a HIGH assessment is not current, and
 * cannot be linked, until someone other than its author has signed it off.
 */
@Service
public class RiskAssessmentAuthoringService {

    private final RiskAssessmentRepository repository;
    private final RiskAssessmentAccessPolicy access;
    private final ReviewIntervalService intervals;
    private final RiskAssessmentEvents events;
    private final AuditPort audit;
    private final Clock clock;

    public RiskAssessmentAuthoringService(RiskAssessmentRepository repository, RiskAssessmentAccessPolicy access,
            ReviewIntervalService intervals, RiskAssessmentEvents events, AuditPort audit, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.intervals = intervals;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param hazards the author's hazards; when empty and a template is named, the template's are copied
     * @param title when blank and a template is named, the template's name
     */
    public record CreateAssessment(String siteCode, String activityType, String locationCode, String title,
            String summary, List<Hazard> hazards, UUID templateId, String idempotencyKey, ActorContext actor,
            SourceChannel channel) {

        /** What a retry must repeat exactly for the key to return the original - everything but who and how. */
        String fingerprint() {
            return sha256(String.valueOf(Arrays.asList(siteCode, activityType, locationCode, title, summary, hazards,
                    templateId)));
        }
    }

    /**
     * Creates the assessment and its first draft. With an {@code Idempotency-Key}, a retry carrying the same
     * key and body returns the assessment the first call created rather than a second one; the same key
     * with a different body is refused.
     */
    @Transactional
    public AssessmentDetail create(CreateAssessment command) {
        ActorContext actor = command.actor();
        access.require(actor, SflPermission.RISK_ASSESSMENT_AUTHOR, command.siteCode(), "RiskAssessment", null);
        String key = command.idempotencyKey() == null || command.idempotencyKey().isBlank() ? null
                : command.idempotencyKey().strip();
        if (key != null) {
            Optional<RiskAssessmentRepository.CreationKey> earlier = repository.findCreationKey(actor.actorId(), key);
            if (earlier.isPresent()) {
                if (!earlier.get().requestHash().equals(command.fingerprint())) {
                    throw new RiskAssessmentException(
                            RiskAssessmentErrorCode.RISK_ASSESSMENT_IDEMPOTENCY_KEY_CONFLICT);
                }
                return detail(requireAssessment(earlier.get().assessmentId()));
            }
        }
        Optional<AssessmentTemplate> template = Optional.ofNullable(command.templateId()).map(this::activeTemplate);

        List<Hazard> hazards = command.hazards() == null || command.hazards().isEmpty()
                ? template.map(AssessmentTemplate::hazards).orElse(List.of())
                : command.hazards();
        String title = command.title() == null || command.title().isBlank()
                ? template.map(AssessmentTemplate::name).orElse(null)
                : command.title();
        String activityType = command.activityType() == null || command.activityType().isBlank()
                ? template.map(AssessmentTemplate::activityType).orElse(null)
                : command.activityType();
        AssessmentContent content = new AssessmentContent(title, command.summary(), hazards);

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String reference = "RA-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        RiskAssessment assessment = RiskAssessment.create(id, command.siteCode(), reference, activityType,
                command.locationCode(), command.templateId(), content.title(), actor.actorId(), now, command.channel(),
                actor.correlationId());
        AssessmentVersion draft = AssessmentVersion.draft(UUID.randomUUID(), id, assessment.siteCode(), 1, content,
                actor.actorId(), actor.principal().displayName(), now, command.channel(), actor.correlationId());

        RiskAssessment saved = repository.saveAssessment(assessment);
        AssessmentVersion savedDraft = repository.saveVersion(draft);
        if (key != null) {
            repository.recordCreationKey(saved.id(), key, command.fingerprint());
        }
        audit.record(actor, command.channel().name(), saved.siteCode(), "RISK_ASSESSMENT_CREATED", "RiskAssessment",
                saved.id().toString(), null, savedDraft, null);
        return detail(saved);
    }

    /** Edits the open draft in place. {@code expectedVersion} is the draft's record version, for optimistic locking. */
    @Transactional
    public AssessmentDetail editDraft(UUID assessmentId, AssessmentContent content, Long expectedVersion,
            ActorContext actor, SourceChannel channel) {
        RiskAssessment assessment = requireAssessment(assessmentId);
        access.require(actor, SflPermission.RISK_ASSESSMENT_AUTHOR, assessment.siteCode(), "RiskAssessment",
                assessmentId.toString());
        AssessmentVersion draft = requireDraft(assessment);
        draft.metadata().requireVersion(expectedVersion);
        Instant now = clock.instant();
        AssessmentVersion edited = repository.saveVersion(draft.edit(content, actor.actorId(), now, channel,
                actor.correlationId()));
        RiskAssessment summarised = repository.saveAssessment(assessment.summarise(edited, actor.actorId(), now,
                channel, actor.correlationId()));
        audit.record(actor, channel.name(), assessment.siteCode(), "RISK_ASSESSMENT_DRAFT_EDITED", "RiskAssessment",
                assessmentId.toString(), draft, edited, null);
        return detail(summarised);
    }

    /**
     * Opens a new draft at the next version number, copied from the current version - the only way to
     * change a published assessment. The person revising is the new version's author.
     */
    @Transactional
    public AssessmentDetail openRevision(UUID assessmentId, ActorContext actor, SourceChannel channel) {
        RiskAssessment assessment = requireAssessment(assessmentId);
        access.require(actor, SflPermission.RISK_ASSESSMENT_AUTHOR, assessment.siteCode(), "RiskAssessment",
                assessmentId.toString());
        if (assessment.currentVersion() == null) {
            throw RiskAssessmentException.invalidState(
                    "Nothing has been published yet. Edit the open draft instead of revising.");
        }
        AssessmentVersion current = requireVersion(assessmentId, assessment.currentVersion());
        int next = assessment.latestVersion() + 1;
        Instant now = clock.instant();
        RiskAssessment opened = assessment.draftOpened(next, actor.actorId(), now, channel, actor.correlationId());
        AssessmentVersion draft = AssessmentVersion.draft(UUID.randomUUID(), assessmentId, assessment.siteCode(), next,
                current.content(), actor.actorId(), actor.principal().displayName(), now, channel,
                actor.correlationId());
        AssessmentVersion savedDraft = repository.saveVersion(draft);
        RiskAssessment saved = repository.saveAssessment(opened);
        audit.record(actor, channel.name(), assessment.siteCode(), "RISK_ASSESSMENT_REVISION_OPENED",
                "RiskAssessment", assessmentId.toString(), current, savedDraft, null);
        return detail(saved);
    }

    /**
     * Publishes the open draft as the current version and supersedes the one before it, in one
     * transaction - so there is never a moment with two current versions, or none.
     */
    @Transactional
    public AssessmentDetail publish(UUID assessmentId, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        RiskAssessment assessment = requireAssessment(assessmentId);
        access.require(actor, SflPermission.RISK_ASSESSMENT_PUBLISH, assessment.siteCode(), "RiskAssessment",
                assessmentId.toString());
        AssessmentVersion draft = requireDraft(assessment);
        draft.metadata().requireVersion(expectedVersion);
        // Before the interval lookup: an empty draft has no level to look one up for, and "Hazard Without
        // Control" is the answer the author needs, not a configuration error.
        PublishPolicy.requirePublishable(draft.content());
        RiskLevel level = draft.riskLevel();
        int intervalDays = intervals.intervalFor(level).intervalDays();

        Instant now = clock.instant();
        Optional<AssessmentVersion> previous = Optional.ofNullable(assessment.currentVersion())
                .map(number -> requireVersion(assessmentId, number));
        // Supersede first. The database holds one PUBLISHED version per assessment (a partial unique
        // index), so publishing the new one while the old is still PUBLISHED is refused - correctly.
        previous.ifPresent(prior -> {
            AssessmentVersion superseded = repository.saveVersion(prior.supersede(actor.actorId(), now, channel,
                    actor.correlationId()));
            events.superseded(superseded, actor);
        });
        AssessmentVersion published = repository.saveVersion(draft.publish(intervalDays, actor.actorId(), now,
                channel, actor.correlationId()));
        RiskAssessment summarised = repository.saveAssessment(assessment.summarise(published, actor.actorId(), now,
                channel, actor.correlationId()));
        audit.record(actor, channel.name(), assessment.siteCode(), "RISK_ASSESSMENT_PUBLISHED", "RiskAssessment",
                assessmentId.toString(), previous.orElse(null), published, null);
        events.published(summarised, published, actor);
        return detail(summarised);
    }

    @Transactional(readOnly = true)
    public AssessmentDetail get(UUID assessmentId, ActorContext actor) {
        RiskAssessment assessment = requireAssessment(assessmentId);
        access.require(actor, SflPermission.RISK_ASSESSMENT_READ, assessment.siteCode(), "RiskAssessment",
                assessmentId.toString());
        return detail(assessment);
    }

    /** One version, superseded or not - S165-01 "remains retrievable for audit". */
    @Transactional(readOnly = true)
    public AssessmentVersion version(UUID assessmentId, int versionNumber, ActorContext actor) {
        RiskAssessment assessment = requireAssessment(assessmentId);
        access.require(actor, SflPermission.RISK_ASSESSMENT_READ, assessment.siteCode(), "RiskAssessment",
                assessmentId.toString());
        return requireVersion(assessmentId, versionNumber);
    }

    public record SearchAssessments(String siteCode, String activityType, RiskLevel riskLevel,
            StandingFilter standing, String text, Paging paging, ActorContext actor) {
    }

    @Transactional(readOnly = true)
    public RiskAssessmentPage<AssessmentSummary> search(SearchAssessments query) {
        access.require(query.actor(), SflPermission.RISK_ASSESSMENT_READ, query.siteCode(), "RiskAssessment", null);
        Instant now = clock.instant();
        return repository.searchAssessments(new AssessmentQuery(normaliseSite(query.siteCode()),
                        query.activityType(), query.riskLevel(), query.standing(), query.text(), now), query.paging())
                .map(assessment -> AssessmentSummary.of(assessment, now));
    }

    AssessmentDetail detail(RiskAssessment assessment) {
        Instant now = clock.instant();
        List<AssessmentVersion> versions = repository.findVersions(assessment.id());
        List<SignOff> signOffs = repository.findSignOffs(assessment.id());
        List<ReviewFlag> flags = repository.findFlags(assessment.id());
        return new AssessmentDetail(AssessmentSummary.of(assessment, now), versions, signOffs, flags);
    }

    RiskAssessment requireAssessment(UUID id) {
        return repository.findAssessment(id).orElseThrow(() -> RiskAssessmentException.notFound("RiskAssessment", id));
    }

    private AssessmentVersion requireDraft(RiskAssessment assessment) {
        if (!assessment.hasOpenDraft()) {
            throw RiskAssessmentException.invalidState("This assessment has no open draft.");
        }
        return requireVersion(assessment.id(), assessment.draftVersion());
    }

    private AssessmentVersion requireVersion(UUID assessmentId, int number) {
        return repository.findVersion(assessmentId, number).orElseThrow(() -> RiskAssessmentException.notFound(
                "AssessmentVersion", assessmentId));
    }

    private AssessmentTemplate activeTemplate(UUID templateId) {
        AssessmentTemplate template = repository.findTemplate(templateId)
                .orElseThrow(() -> RiskAssessmentException.notFound("AssessmentTemplate", templateId));
        if (!template.active()) {
            throw RiskAssessmentException.invalidState("That template has been retired.");
        }
        return template;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    private static String normaliseSite(String siteCode) {
        return siteCode == null ? null : siteCode.strip().toUpperCase(Locale.ROOT);
    }
}
