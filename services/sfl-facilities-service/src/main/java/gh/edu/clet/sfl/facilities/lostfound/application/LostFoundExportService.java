package gh.edu.clet.sfl.facilities.lostfound.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.application.RegisterExport;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Found items as a CSV file (SRS 4.2, NFR-AUD1). Exporting needs its own grant, a stated reason, and is audited with the row
 * count and the reason. The rows come from the module's own list, so the caller's read permission and every masking rule
 * (private description and finder are blank without the private-read grant) apply to the file exactly as they do to the screen.
 */
@Service
public class LostFoundExportService {

    private final LostFoundSupport support;
    private final LostFoundItemService items;

    public LostFoundExportService(LostFoundSupport support, LostFoundItemService items) {
        this.support = support;
        this.items = items;
    }

    @org.springframework.transaction.annotation.Transactional
    public RegisterExport.Result export(String siteCode, String status, String reason, Caller caller) {
        String site = LostFoundSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_REGISTER_EXPORT, site, "FoundItem", "export");
        String why = RegisterExport.reason(reason);
        List<FoundItem> rows = RegisterExport.collect(page -> items.list(site, status, null, false, page, 100, caller).items());
        RegisterExport.Result result = RegisterExport.build("S179", "items", site, caller.actor().actorId(), why, support.now(),
                List.of("Reference", "Claim reference", "Category", "Public description", "Private description", "Found at", "Found location", "Finder", "Status", "Unsafe", "Retention until"), rows, r -> List.<Object>of(r.reference(), r.claimReference(), r.category(), r.publicDescription(), r.privateDescription(), r.foundAt(), r.foundLocation(), r.finderReference(), r.status(), r.unsafe(), r.retentionUntil()));
        support.audit(caller, AuditAction.REGISTER_EXPORTED, "FoundItem", UUID.nameUUIDFromBytes(("S179:" + site).getBytes()), site, null,
                "items: " + result.rows() + " row(s)" + (result.truncated() ? " (truncated)" : "") + "; reason: " + why);
        return result;
    }
}
