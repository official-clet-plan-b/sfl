package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ActivityTypes;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentStanding;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.HazardType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ObservedActivityType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlagStatus;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.ReviewSchedulePolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S165-03: hazard frequency across the library, coverage gaps against what the platform actually
 * does, and the dashboard that puts both next to the review queue.
 *
 * <p>Coverage is per site: a current hot-work assessment at one centre does not cover hot work at another.
 * And it is judged by the currency rule - an activity whose only assessment has lapsed is a gap, because
 * nothing current covers it, which is what a permit or project trying to link one would find.
 */
@Service
public class CoverageService {

    private final RiskAssessmentRepository repository;
    private final RiskAssessmentAccessPolicy access;
    private final ReviewIntervalService intervals;
    private final Clock clock;

    public CoverageService(RiskAssessmentRepository repository, RiskAssessmentAccessPolicy access,
            ReviewIntervalService intervals, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.intervals = intervals;
        this.clock = clock;
    }

    /**
     * Records that a system used an activity type at a site. Called by the platform - the inbound S176 feed
     * and S163 incidents - never by an operator, so not permission-checked. Upserts one row per site,
     * activity type and source.
     */
    @Transactional
    public ObservedActivityType observe(String siteCode, String activityType, String sourceSystem,
            String sourceReference, ActorContext actor) {
        String site = siteCode.strip().toUpperCase(Locale.ROOT);
        String activity = ActivityTypes.normalise(activityType);
        if (activity == null) {
            throw new IllegalArgumentException("An observed activity type cannot be blank");
        }
        Instant now = clock.instant();
        ObservedActivityType observation = repository.findObservation(site, activity, sourceSystem)
                .map(existing -> existing.seenAgain(sourceReference, actor.actorId(), now, SourceChannel.INTEGRATION,
                        actor.correlationId()))
                .orElseGet(() -> ObservedActivityType.firstSeen(UUID.randomUUID(), site, activity, sourceSystem,
                        sourceReference, actor.actorId(), now, SourceChannel.INTEGRATION, actor.correlationId()));
        return repository.saveObservation(observation);
    }

    /**
     * Every activity type known at a site - observed in use, or already scoped by an assessment - for a
     * picker that should offer what the platform actually does rather than a free-text box. Sorted.
     */
    @Transactional(readOnly = true)
    public List<String> activityTypes(String siteCode, ActorContext actor) {
        access.require(actor, SflPermission.RISK_ASSESSMENT_READ, siteCode, "RiskAssessment", null);
        String site = site(siteCode);
        Set<String> known = new java.util.TreeSet<>();
        repository.findObservations(site).forEach(o -> known.add(o.activityType()));
        repository.findAssessments(site).stream().map(RiskAssessment::activityType).filter(a -> a != null)
                .forEach(known::add);
        return List.copyOf(known);
    }

    // ---- hazard frequency ---------------------------------------------------------------------------

    /**
     * @param occurrences how many hazards of this type appear across current assessments
     * @param assessments how many distinct current assessments name it
     * @param highestResidual the highest residual level any of them leaves it at
     */
    public record HazardFrequency(HazardType hazardType, long occurrences, long assessments,
            RiskLevel highestResidual) {
    }

    /** S165-03: "Analytics roll up hazard type frequency across all current assessments." Most frequent first. */
    @Transactional(readOnly = true)
    public List<HazardFrequency> hazardFrequency(String siteCode, ActorContext actor) {
        access.require(actor, SflPermission.RISK_ASSESSMENT_ANALYTICS_READ, siteCode, "RiskAssessment", null);
        return hazardFrequency(currentVersions(site(siteCode), clock.instant()));
    }

    // ---- coverage -----------------------------------------------------------------------------------

    /** Where an observed activity type was used - so a gap names its origin. */
    public record ObservationSource(String sourceSystem, long occurrences, Instant firstSeenAt, Instant lastSeenAt,
            String lastSourceReference) {
    }

    /**
     * One observed activity type and whether anything current covers it.
     *
     * @param assessments every assessment at the site scoped to this activity type, current or not - so a
     *        gap caused by a lapse says "renew RA-1234", not "write one"
     */
    public record CoverageLine(String activityType, boolean covered, long occurrences,
            List<ObservationSource> sources, List<AssessmentSummary> assessments) {
    }

    public record CoverageReport(String siteCode, int observedActivityTypes, int covered, List<CoverageLine> gaps,
            List<CoverageLine> coveredLines) {
    }

    /**
     * S165-03: "Activity types observed across modules -> matched against current assessments -> gaps
     * surfaced." Gaps first, most-used first, so the biggest and most-neglected comes to the top.
     */
    @Transactional(readOnly = true)
    public CoverageReport coverage(String siteCode, ActorContext actor) {
        access.require(actor, SflPermission.RISK_ASSESSMENT_ANALYTICS_READ, siteCode, "RiskAssessment", null);
        return coverage(site(siteCode), clock.instant());
    }

    // ---- dashboard ----------------------------------------------------------------------------------

    /**
     * The landing figures, all published by the service from records it holds - none derived by the client.
     *
     * @param dueSoon current assessments inside their level's reminder window
     * @param openDrafts assessments with a draft open, whether a first draft or a revision
     */
    public record Dashboard(String siteCode, long totalAssessments, Map<AssessmentStanding, Long> byStanding,
            Map<RiskLevel, Long> currentByRiskLevel, long dueSoon, long openDrafts, long openReviewFlags,
            long deferredReviewFlags, long coverageGaps, List<HazardFrequency> topHazards) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(String siteCode, ActorContext actor) {
        access.require(actor, SflPermission.RISK_ASSESSMENT_READ, siteCode, "RiskAssessment", null);
        String site = site(siteCode);
        Instant now = clock.instant();
        List<RiskAssessment> all = repository.findAssessments(site);

        Map<AssessmentStanding, Long> byStanding = new EnumMap<>(AssessmentStanding.class);
        for (AssessmentStanding standing : AssessmentStanding.values()) {
            byStanding.put(standing, 0L);
        }
        Map<RiskLevel, Long> byLevel = new EnumMap<>(RiskLevel.class);
        for (RiskLevel level : RiskLevel.values()) {
            byLevel.put(level, 0L);
        }
        long dueSoon = 0;
        long openDrafts = 0;
        for (RiskAssessment assessment : all) {
            AssessmentStanding standing = assessment.standing(now);
            byStanding.merge(standing, 1L, Long::sum);
            if (assessment.hasOpenDraft()) {
                openDrafts++;
            }
            if (standing == AssessmentStanding.CURRENT) {
                byLevel.merge(assessment.currentRiskLevel(), 1L, Long::sum);
                int lead = intervals.intervalFor(assessment.currentRiskLevel()).reminderLeadDays();
                if (ReviewSchedulePolicy.reminderDue(assessment.currentReviewDueAt(), lead, now)) {
                    dueSoon++;
                }
            }
        }
        List<HazardFrequency> hazards = hazardFrequency(currentVersions(site, now));
        return new Dashboard(site, all.size(), byStanding, byLevel, dueSoon, openDrafts,
                repository.countFlags(site, ReviewFlagStatus.OPEN), repository.countFlags(site, ReviewFlagStatus.DEFERRED),
                coverage(site, now).gaps().size(), hazards.stream().limit(5).toList());
    }

    // ---- shared -------------------------------------------------------------------------------------

    private CoverageReport coverage(String site, Instant now) {
        Map<String, List<ObservedActivityType>> observed = repository.findObservations(site).stream()
                .collect(Collectors.groupingBy(ObservedActivityType::activityType, LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, List<RiskAssessment>> byActivity = repository.findAssessments(site).stream()
                .filter(assessment -> assessment.activityType() != null)
                .collect(Collectors.groupingBy(RiskAssessment::activityType));

        List<CoverageLine> gaps = new ArrayList<>();
        List<CoverageLine> covered = new ArrayList<>();
        for (Map.Entry<String, List<ObservedActivityType>> entry : observed.entrySet()) {
            List<RiskAssessment> matching = byActivity.getOrDefault(entry.getKey(), List.of());
            boolean isCovered = matching.stream().anyMatch(a -> a.standing(now) == AssessmentStanding.CURRENT);
            List<ObservationSource> sources = entry.getValue().stream()
                    .sorted(Comparator.comparing(ObservedActivityType::lastSeenAt).reversed())
                    .map(o -> new ObservationSource(o.sourceSystem(), o.occurrences(), o.firstSeenAt(),
                            o.lastSeenAt(), o.lastSourceReference()))
                    .toList();
            long occurrences = entry.getValue().stream().mapToLong(ObservedActivityType::occurrences).sum();
            CoverageLine line = new CoverageLine(entry.getKey(), isCovered, occurrences, sources,
                    matching.stream().map(a -> AssessmentSummary.of(a, now)).toList());
            (isCovered ? covered : gaps).add(line);
        }
        Comparator<CoverageLine> mostUsedFirst = Comparator.comparingLong(CoverageLine::occurrences).reversed()
                .thenComparing(CoverageLine::activityType);
        gaps.sort(mostUsedFirst);
        covered.sort(mostUsedFirst);
        return new CoverageReport(site, observed.size(), covered.size(), gaps, covered);
    }

    /** The current version of every assessment at the site whose standing is CURRENT. */
    private List<AssessmentVersion> currentVersions(String site, Instant now) {
        Set<UUID> current = new HashSet<>();
        Map<UUID, Integer> currentNumber = new LinkedHashMap<>();
        for (RiskAssessment assessment : repository.findPublishedAssessments(site)) {
            if (assessment.standing(now) == AssessmentStanding.CURRENT) {
                current.add(assessment.id());
                currentNumber.put(assessment.id(), assessment.currentVersion());
            }
        }
        return repository.findPublishedVersions(site).stream()
                .filter(v -> current.contains(v.assessmentId())
                        && currentNumber.get(v.assessmentId()) == v.versionNumber())
                .toList();
    }

    private static List<HazardFrequency> hazardFrequency(List<AssessmentVersion> versions) {
        Map<HazardType, long[]> counts = new EnumMap<>(HazardType.class);
        Map<HazardType, RiskLevel> highest = new EnumMap<>(HazardType.class);
        for (AssessmentVersion version : versions) {
            Set<HazardType> seenInThisAssessment = new HashSet<>();
            for (Hazard hazard : version.content().hazards()) {
                long[] count = counts.computeIfAbsent(hazard.hazardType(), type -> new long[2]);
                count[0]++;
                if (seenInThisAssessment.add(hazard.hazardType())) {
                    count[1]++;
                }
                highest.merge(hazard.hazardType(), hazard.residualRisk().level(),
                        (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        return counts.entrySet().stream()
                .map(e -> new HazardFrequency(e.getKey(), e.getValue()[0], e.getValue()[1], highest.get(e.getKey())))
                .sorted(Comparator.comparingLong(HazardFrequency::occurrences).reversed()
                        .thenComparing(HazardFrequency::hazardType))
                .toList();
    }

    private static String site(String siteCode) {
        return siteCode.strip().toUpperCase(Locale.ROOT);
    }
}
