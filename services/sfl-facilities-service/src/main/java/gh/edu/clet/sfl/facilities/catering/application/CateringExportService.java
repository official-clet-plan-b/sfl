package gh.edu.clet.sfl.facilities.catering.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.catering.domain.CateringService;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.application.RegisterExport;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Catering services as a CSV file (SRS 4.2, NFR-AUD1). Exporting needs its own grant, a stated reason, and is audited with the row
 * count and the reason. The rows come from the module's own list, so the caller's read permission and every masking rule
 * (dietary needs are not part of this file) apply to the file exactly as they do to the screen.
 */
@Service
public class CateringExportService {

    private final CateringSupport support;
    private final CateringServiceService services;

    public CateringExportService(CateringSupport support, CateringServiceService services) {
        this.support = support;
        this.services = services;
    }

    @org.springframework.transaction.annotation.Transactional
    public RegisterExport.Result export(String siteCode, String status, String reason, Caller caller) {
        String site = CateringSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_REGISTER_EXPORT, site, "CateringService", "export");
        String why = RegisterExport.reason(reason);
        List<CateringService> rows = RegisterExport.collect(page -> services.list(site, status, page, 100, caller).items());
        RegisterExport.Result result = RegisterExport.build("S172", "services", site, caller.actor().actorId(), why, support.now(),
                List.of("Reference", "Title", "Context", "Context reference", "Service date", "Planned portions", "Delivered portions", "Status", "Requested by", "Approved by", "Purchase reference", "Invoice reference", "Finance state"), rows, r -> java.util.Arrays.<Object>asList(r.reference(), r.title(), r.contextType(), r.contextReference(), r.serviceDate(), r.plannedPortions(), r.deliveredPortions(), r.status(), r.requestedBy(), r.approvedBy(), r.purchaseReference(), r.invoiceReference(), r.financeState()));
        support.audit(caller, AuditAction.REGISTER_EXPORTED, "CateringService", UUID.nameUUIDFromBytes(("S172:" + site).getBytes()), site, null,
                "services: " + result.rows() + " row(s)" + (result.truncated() ? " (truncated)" : "") + "; reason: " + why);
        return result;
    }
}
