package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitPage;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalDecision;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalStage;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitApproval;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitFlag;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EscalationLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.CompetencyPolicy;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of S164: the register and a permit's full record, the dashboard (open permits by type and risk level, permits
 * nearing expiry, overdue close-outs, isolation status per zone, competence exceptions), analytics, and the statutory export.
 */
@Service
public class PermitQueryService {

    private static final int EXPORT_CAP = 2_000;

    private final PermitRepository repository;
    private final PermitRecords records;
    private final PermitAccessPolicy access;
    private final PermitMonitoringService monitoring;
    private final AuditPort audit;
    private final Clock clock;

    public PermitQueryService(PermitRepository repository, PermitRecords records, PermitAccessPolicy access, PermitMonitoringService monitoring, AuditPort audit,
            Clock clock) {
        this.repository = repository;
        this.records = records;
        this.access = access;
        this.monitoring = monitoring;
        this.audit = audit;
        this.clock = clock;
    }

    public record Search(String siteCode, UUID permitTypeId, PermitStatus status, String contractor, Instant from, Instant to, boolean openOnly,
            boolean overdueOnly, String text, Paging paging) {
    }

    @Transactional(readOnly = true)
    public PermitPage<Permit> search(Search s, Caller caller) {
        access.requirePermission(caller.actor(), SflPermission.PERMIT_READ, "Permit");
        return repository.search(query(s, access.sites(caller.actor(), s.siteCode(), "Permit"), s.paging()));
    }

    @Transactional(readOnly = true)
    public PermitDetail get(UUID id, Caller caller) {
        Permit permit = records.require(id);
        access.require(caller.actor(), SflPermission.PERMIT_READ, permit.siteCode(), "Permit", id.toString());
        return records.detail(permit);
    }

    // ---- dashboard --------------------------------------------------------------------------------

    public record TypeRiskCell(String typeCode, String typeName, String riskLevel, long open, long active, long suspended) {
    }

    public record ZoneIsolation(String zone, long permits, long required, long verified, long removed) {
    }

    public record CompetencyExceptionRow(String permitReference, UUID permitId, String contractor, String worker, String competency, String reason) {
    }

    public record Dashboard(String siteCode, Instant asOf, long open, long awaitingVerification, long awaitingApproval, List<TypeRiskCell> openByTypeAndRisk,
            List<Permit> nearingExpiry, List<Permit> overdueCloseOuts, List<ZoneIsolation> isolationByZone, List<CompetencyExceptionRow> competencyExceptions,
            List<PermitFlag> openFlags, long warnMinutes) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(String siteCode, Caller caller) {
        access.requirePermission(caller.actor(), SflPermission.PERMIT_READ, "Permit");
        List<String> sites = access.sites(caller.actor(), siteCode, "Permit");
        Instant now = clock.instant();
        Duration warn = monitoring.warnBefore();
        List<Permit> open = repository.openPermits(sites);
        Map<UUID, PermitType> types = repository.types(false).stream().collect(Collectors.toMap(PermitType::id, Function.identity()));

        Map<String, long[]> cells = new LinkedHashMap<>();
        for (Permit p : open) {
            PermitType t = types.get(p.permitTypeId());
            String key = t.code() + "|" + t.riskLevel();
            long[] c = cells.computeIfAbsent(key, k -> new long[3]);
            c[0]++;
            if (p.status() == PermitStatus.ACTIVE) {
                c[1]++;
            }
            if (p.status() == PermitStatus.SUSPENDED) {
                c[2]++;
            }
        }
        List<TypeRiskCell> byTypeAndRisk = cells.entrySet().stream().map(e -> {
            String[] k = e.getKey().split("\\|");
            PermitType t = types.values().stream().filter(x -> x.code().equals(k[0])).findFirst().orElseThrow();
            return new TypeRiskCell(k[0], t.name(), k[1], e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        }).toList();

        List<Permit> nearing = open.stream().filter(p -> !p.endsAt().isBefore(now) && !p.endsAt().isAfter(now.plus(warn))).toList();
        List<Permit> overdue = open.stream().filter(p -> p.overdue(now)).sorted(Comparator.comparing(Permit::endsAt)).toList();

        Map<String, long[]> zones = new LinkedHashMap<>();
        for (Permit p : open) {
            long[] z = zones.computeIfAbsent(p.zoneCode() == null ? "No zone named" : p.zoneCode(), k -> new long[4]);
            z[0]++;
            for (Isolation i : repository.isolations(p.id())) {
                z[1 + (i.status() == IsolationStatus.REQUIRED ? 0 : i.status() == IsolationStatus.VERIFIED ? 1 : 2)]++;
            }
        }
        List<ZoneIsolation> isolation = zones.entrySet().stream().map(e -> new ZoneIsolation(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                e.getValue()[3])).toList();

        List<CompetencyExceptionRow> exceptions = new ArrayList<>();
        List<Permit> beingPrepared = repository.search(query(new Search(null, null, null, null, null, null, false, false, null, new Paging(0, 200, null)),
                sites, new Paging(0, 200, null))).content().stream().filter(p -> p.status() == PermitStatus.SUBMITTED || p.status() == PermitStatus.ISOLATION_VERIFIED
                        || p.status() == PermitStatus.STAGE1_APPROVED || p.status() == PermitStatus.RESUMPTION_PENDING).toList();
        List<Permit> toCheck = new ArrayList<>(open);
        toCheck.addAll(beingPrepared);
        for (Permit p : toCheck) {
            for (CompetencyPolicy.Exception e : records.competencyExceptions(p, types.get(p.permitTypeId()), p.endsAt())) {
                exceptions.add(new CompetencyExceptionRow(p.reference(), p.id(), p.contractorReference(), e.worker().displayName(), e.competency(), e.reason().name()));
            }
        }
        long awaitingVerification = beingPrepared.stream().filter(p -> p.status() == PermitStatus.SUBMITTED).count();
        long awaitingApproval = beingPrepared.stream().filter(p -> p.status() != PermitStatus.SUBMITTED).count();
        return new Dashboard(siteCode == null ? null : siteCode.strip().toUpperCase(), now, open.size(), awaitingVerification, awaitingApproval, byTypeAndRisk, nearing,
                overdue, isolation, exceptions, repository.openFlags(sites), warn.toMinutes());
    }

    // ---- analytics --------------------------------------------------------------------------------

    public record Analytics(Instant from, Instant to, long permits, List<PermitRepository.CountRow> byType, List<PermitRepository.CountRow> byContractor,
            List<PermitRepository.CountRow> byOutcome, Double meanOpenHours, long flaggedForIncident, double incidentCorrelationPercent) {
    }

    @Transactional(readOnly = true)
    public Analytics analytics(String siteCode, Instant from, Instant to, Caller caller) {
        access.requirePermission(caller.actor(), SflPermission.PERMIT_ANALYTICS_READ, "Permit");
        List<String> sites = access.sites(caller.actor(), siteCode, "Permit");
        List<PermitRepository.CountRow> byType = repository.volumeByType(sites, from, to);
        long total = byType.stream().mapToLong(PermitRepository.CountRow::count).sum();
        long flagged = repository.flaggedForIncident(sites, from, to);
        return new Analytics(from, to, total, byType, repository.volumeByContractor(sites, from, to), repository.volumeByOutcome(sites, from, to),
                repository.meanOpenHours(sites, from, to), flagged, total == 0 ? 0 : Math.round(flagged * 1000.0 / total) / 10.0);
    }

    // ---- statutory export (S164-05) ---------------------------------------------------------------

    public record ExportFile(String fileName, String csv, int permits, int incomplete, boolean truncated) {
    }

    /**
     * The register with each permit's complete lifecycle on one row: request, risk assessment reference, approvals, isolation
     * verification, suspensions, extensions, close-out. A permit that has not closed says so in its own column and in the header -
     * a partial lifecycle is never presented as a complete one.
     */
    @Transactional
    public ExportFile export(Search s, String reason, Caller caller) {
        access.requirePermission(caller.actor(), SflPermission.PERMIT_EXPORT, "Permit");
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 10) {
            throw new PermitException(PermitErrorCode.PERMIT_EXPORT_REASON_REQUIRED);
        }
        List<String> sites = access.sites(caller.actor(), s.siteCode(), "Permit");
        List<Permit> permits = new ArrayList<>();
        for (int page = 0; permits.size() <= EXPORT_CAP; page++) {
            List<Permit> chunk = repository.search(query(s, sites, new Paging(page, 100, "startsAt,asc"))).content();
            if (chunk.isEmpty()) {
                break;
            }
            permits.addAll(chunk);
        }
        boolean truncated = permits.size() > EXPORT_CAP;
        List<Permit> kept = truncated ? permits.subList(0, EXPORT_CAP) : permits;
        Instant now = clock.instant();
        List<String> lines = new ArrayList<>();
        int incomplete = 0;
        for (Permit p : kept) {
            List<PermitApproval> approvals = repository.approvals(p.id());
            List<Isolation> isolations = repository.isolations(p.id());
            boolean complete = p.status() == PermitStatus.CLOSED || p.status() == PermitStatus.REJECTED || p.status() == PermitStatus.CANCELLED;
            if (!complete) {
                incomplete++;
            }
            lines.add(PermitCsv.line(List.of(p.reference(), p.workType(), p.status(), p.siteCode(), p.locationCode(), p.zoneCode() == null ? "" : p.zoneCode(), p.title(),
                    p.contractorReference() == null ? "" : p.contractorReference(), p.originSystem() + (p.originReference() == null ? "" : " " + p.originReference()),
                    p.requestedBy(), p.submittedAt() == null ? "" : p.submittedAt(), p.startsAt(), p.endsAt(),
                    p.riskAssessmentReference() == null ? "" : p.riskAssessmentReference() + " v" + p.riskAssessmentVersion() + " " + p.riskLevel(),
                    isolations.size() + " (" + isolations.stream().filter(i -> i.status() != IsolationStatus.REQUIRED).count() + " verified, "
                            + isolations.stream().filter(i -> i.status() == IsolationStatus.REMOVED).count() + " removed)",
                    isolations.stream().map(Isolation::verifiedBy).filter(v -> v != null).distinct().collect(Collectors.joining("; ")),
                    approver(approvals, ApprovalStage.ISSUING_AUTHORITY), approver(approvals, ApprovalStage.SAFETY_SIGN_OFF), p.issuedAt() == null ? "" : p.issuedAt(),
                    repository.suspensions(p.id()).size(), repository.extensions(p.id()).size(),
                    p.workCompletedBy() == null ? "" : p.workCompletedBy() + " " + p.workCompletedAt(), p.completionStatement() == null ? "" : p.completionStatement(),
                    isolations.stream().map(Isolation::removedBy).filter(v -> v != null).distinct().collect(Collectors.joining("; ")),
                    p.closedBy() == null ? "" : p.closedBy() + " " + p.closedAt(),
                    repository.flags(p.id()).stream().map(f -> f.flagType() + " " + f.reference()).collect(Collectors.joining("; ")),
                    complete ? "COMPLETE" : "INCOMPLETE - permit is still " + p.status())));
        }
        StringBuilder out = new StringBuilder();
        out.append("# CLET S164 permit register - STATUTORY EVIDENCE, CONFIDENTIAL\n");
        out.append("# Exported by ").append(PermitCsv.oneLine(caller.id())).append(" at ").append(now).append(" for ")
                .append(s.siteCode() == null ? "all permitted sites" : PermitCsv.oneLine(s.siteCode())).append('\n');
        out.append("# Reason: ").append(PermitCsv.oneLine(why)).append('\n');
        out.append("# ").append(kept.size()).append(" permit(s); ").append(incomplete).append(" with an incomplete lifecycle (still open) - their rows say so\n");
        if (truncated) {
            out.append("# Truncated at ").append(EXPORT_CAP).append(" permits: narrow the filter to export the rest\n");
        }
        out.append(PermitCsv.line(List.of("Reference", "Type", "Status", "Site", "Location", "Zone", "Title", "Contractor", "Origin", "Requested by", "Submitted at",
                "Starts", "Ends", "Risk assessment", "Isolations", "Isolation verified by", "Issuing authority", "Safety sign-off", "Issued at", "Suspensions",
                "Extensions", "Work completed", "Completion statement", "Isolation removed by", "Closed", "Flags", "Lifecycle"))).append('\n');
        lines.forEach(l -> out.append(l).append('\n'));
        audit.record(caller.actor(), caller.channel().name(), s.siteCode() == null ? "*" : s.siteCode().strip().toUpperCase(), "PERMIT_REGISTER_EXPORTED", "Permit",
                UUID.nameUUIDFromBytes(("S164:" + s.siteCode()).getBytes()).toString(), null, kept.size() + " permit(s), " + incomplete + " incomplete", why);
        String name = "s164-permits-" + (s.siteCode() == null ? "all" : s.siteCode().strip().toLowerCase()) + "-" + now.toString().substring(0, 10) + ".csv";
        return new ExportFile(name.replaceAll("[^a-z0-9.-]+", "-"), out.toString(), kept.size(), incomplete, truncated);
    }

    private static String approver(List<PermitApproval> approvals, ApprovalStage stage) {
        return approvals.stream().filter(a -> a.stage() == stage && a.decision() == ApprovalDecision.APPROVED).map(a -> a.decidedBy() + " " + a.decidedAt())
                .collect(Collectors.joining("; "));
    }

    private PermitRepository.PermitQuery query(Search s, List<String> sites, Paging paging) {
        return new PermitRepository.PermitQuery(sites, s.permitTypeId(), s.status(), s.contractor(), s.from(), s.to(), s.openOnly(), s.overdueOnly(),
                clock.instant(), s.text(), paging);
    }
}
