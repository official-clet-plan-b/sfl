package gh.edu.clet.sfl.facilities.hygiene.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.application.RegisterExport;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Hygiene findings as a CSV file (SRS 4.2, NFR-AUD1). Exporting needs its own grant, a stated reason, and is audited with the row
 * count and the reason. The rows come from the module's own list, so the caller's read permission and every masking rule
 * (none beyond site scope) apply to the file exactly as they do to the screen.
 */
@Service
public class HygieneExportService {

    private final HygieneSupport support;
    private final HygieneFindingService findings;

    public HygieneExportService(HygieneSupport support, HygieneFindingService findings) {
        this.support = support;
        this.findings = findings;
    }

    @org.springframework.transaction.annotation.Transactional
    public RegisterExport.Result export(String siteCode, String status, String reason, Caller caller) {
        String site = HygieneSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_REGISTER_EXPORT, site, "HygieneFinding", "export");
        String why = RegisterExport.reason(reason);
        List<HygieneFinding> rows = RegisterExport.collect(page -> findings.list(site, status, null, null, false, page, 100, caller).items());
        RegisterExport.Result result = RegisterExport.build("S170", "findings", site, caller.actor().actorId(), why, support.now(),
                List.of("Reference", "Title", "Category", "Severity", "Status", "Owner", "Target date", "Escalation", "Work order (S153)", "Incident (S163)", "Created"), rows, r -> java.util.Arrays.<Object>asList(r.reference(), r.title(), r.category(), r.severity(), r.status(), r.ownerReference(), r.targetDate(), r.escalationLevel(), r.workOrderNumber(), r.incidentReference(), r.createdAt()));
        support.audit(caller, AuditAction.REGISTER_EXPORTED, "HygieneFinding", UUID.nameUUIDFromBytes(("S170:" + site).getBytes()), site, null,
                "findings: " + result.rows() + " row(s)" + (result.truncated() ? " (truncated)" : "") + "; reason: " + why);
        return result;
    }
}
