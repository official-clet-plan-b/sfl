package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository.CapaCounts;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository.SiteDrillStats;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillComplianceService.ComplianceRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S175-04/-05's oversight view: per-site completion and participation, where each site stands against its
 * required frequencies, and the drill corrective actions still open. Compliance rows are included only for a caller
 * entitled to read compliance.
 */
@Service
public class DrillDashboardService {

    private final DrillRepository repository;
    private final DrillRecords records;
    private final DrillAccessPolicy access;
    private final DrillComplianceService compliance;
    private final Clock clock;

    public DrillDashboardService(DrillRepository repository, DrillRecords records, DrillAccessPolicy access,
            DrillComplianceService compliance, Clock clock) {
        this.repository = repository;
        this.records = records;
        this.access = access;
        this.compliance = compliance;
        this.clock = clock;
    }

    /** @param since how far back "executed" and "participation" look */
    public record Dashboard(Instant since, List<SiteDrillStats> sites, CapaCounts correctiveActions,
            List<ComplianceRow> compliance) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(String siteCode, int windowDays, ActorContext actor) {
        access.requirePermission(actor, SflPermission.DRILL_READ, "Drill");
        List<String> sites = access.sites(actor, siteCode, "Drill");
        Instant since = clock.instant().minus(Duration.ofDays(Math.max(1, Math.min(windowDays, 3650))));
        return new Dashboard(since, repository.siteStats(sites, since), repository.capaCounts(sites, records.today()),
                access.has(actor, SflPermission.DRILL_COMPLIANCE_READ) ? compliance.rows(sites) : List.of());
    }
}
