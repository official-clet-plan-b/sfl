package gh.edu.clet.sfl.facilities.lease.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.application.RegisterExport;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Lease and tenancy agreements as a CSV file (SRS 4.2, NFR-AUD1). Exporting needs its own grant, a stated reason, and is audited with the row
 * count and the reason. The rows come from the module's own list, so the caller's read permission and every masking rule
 * (rent and deposit are blank without the financial grant) apply to the file exactly as they do to the screen.
 */
@Service
public class LeaseExportService {

    private final LeaseSupport support;
    private final LeaseAgreementService agreements;

    public LeaseExportService(LeaseSupport support, LeaseAgreementService agreements) {
        this.support = support;
        this.agreements = agreements;
    }

    @org.springframework.transaction.annotation.Transactional
    public RegisterExport.Result export(String siteCode, String status, String reason, Caller caller) {
        String site = LeaseSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_REGISTER_EXPORT, site, "Agreement", "export");
        String why = RegisterExport.reason(reason);
        List<Agreement> rows = RegisterExport.collect(page -> agreements.list(site, status, null, null, page, 100, caller).items());
        RegisterExport.Result result = RegisterExport.build("S177", "agreements", site, caller.actor().actorId(), why, support.now(),
                List.of("Reference", "Title", "Kind", "Direction", "Property", "Owner", "Counterparty", "Counterparty state", "Start", "End", "Notice days", "Notice date", "Renewal", "Rent review", "Annual rent", "Deposit", "Currency", "Status", "Version"), rows, r -> java.util.Arrays.<Object>asList(r.reference(), r.title(), r.kind(), r.direction(), r.propertyReference(), r.ownerReference(), r.counterpartyReference(), r.counterpartyState(), r.startDate(), r.endDate(), r.noticeDays(), r.noticeDate(), r.renewalType(), r.rentReviewDate(), r.annualRent(), r.depositAmount(), r.currency(), r.status(), r.versionNumber()));
        support.audit(caller, AuditAction.REGISTER_EXPORTED, "Agreement", UUID.nameUUIDFromBytes(("S177:" + site).getBytes()), site, null,
                "agreements: " + result.rows() + " row(s)" + (result.truncated() ? " (truncated)" : "") + "; reason: " + why);
        return result;
    }
}
