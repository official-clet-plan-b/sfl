package gh.edu.clet.sfl.facilities.waste.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCollection;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.application.RegisterExport;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Waste collections as a CSV file (SRS 4.2, NFR-AUD1). Exporting needs its own grant, a stated reason, and is audited with the row
 * count and the reason. The rows come from the module's own list, so the caller's read permission and every masking rule
 * (estimates are labelled by quantity basis) apply to the file exactly as they do to the screen.
 */
@Service
public class WasteExportService {

    private final WasteSupport support;
    private final WasteCollectionService collections;

    public WasteExportService(WasteSupport support, WasteCollectionService collections) {
        this.support = support;
        this.collections = collections;
    }

    @org.springframework.transaction.annotation.Transactional
    public RegisterExport.Result export(String siteCode, String status, String reason, Caller caller) {
        String site = WasteSupport.code(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_REGISTER_EXPORT, site, "WasteCollection", "export");
        String why = RegisterExport.reason(reason);
        List<WasteCollection> rows = RegisterExport.collect(page -> collections.list(site, status, null, false, page, 100, caller).items());
        RegisterExport.Result result = RegisterExport.build("S178", "collections", site, caller.actor().actorId(), why, support.now(),
                List.of("Reference", "Scheduled", "Collected", "Hazardous", "Quantity", "Unit", "Quantity (kg)", "Basis", "Manifest", "Certificate", "Contaminated", "Status"), rows, r -> java.util.Arrays.<Object>asList(r.reference(), r.scheduledFor(), r.collectedOn(), r.hazardous(), r.quantity(), r.unit(), r.quantityKg(), r.quantityBasis(), r.manifestReference(), r.certificateReference(), r.contaminated(), r.status()));
        support.audit(caller, AuditAction.REGISTER_EXPORTED, "WasteCollection", UUID.nameUUIDFromBytes(("S178:" + site).getBytes()), site, null,
                "collections: " + result.rows() + " row(s)" + (result.truncated() ? " (truncated)" : "") + "; reason: " + why);
        return result;
    }
}
