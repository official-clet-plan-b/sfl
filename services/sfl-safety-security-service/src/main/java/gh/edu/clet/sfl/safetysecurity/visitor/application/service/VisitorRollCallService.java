package gh.edu.clet.sfl.safetysecurity.visitor.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.visitor.application.contract.OnSiteVisitors;
import gh.edu.clet.sfl.safetysecurity.visitor.application.port.VisitorRepository;
import gh.edu.clet.sfl.safetysecurity.visitor.domain.model.VisitorVisit;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S160-01: "visitor roll-call views" - who is currently on site for a given CLET site, for
 * emergency muster and routine reception oversight.
 */
@Service
public class VisitorRollCallService implements OnSiteVisitors {

    private final VisitorRepository repository;
    private final VisitorAccessPolicy access;

    public VisitorRollCallService(VisitorRepository repository, VisitorAccessPolicy access) {
        this.repository = repository;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<VisitorVisit> rollCall(String siteCode, ActorContext actor) {
        access.require(actor, SflPermission.VISITOR_ROLLCALL_READ, siteCode, "VisitorVisit", null);
        return repository.findOnSite(siteCode);
    }

    /** {@inheritDoc} The same visits the roll-call screen lists, as muster references. */
    @Override
    @Transactional(readOnly = true)
    public List<OnSiteVisitor> onSite(String siteCode) {
        return repository.findOnSite(siteCode.strip().toUpperCase(Locale.ROOT)).stream()
                .map(visit -> new OnSiteVisitor(
                        visit.badgeNumber() != null && !visit.badgeNumber().isBlank() ? visit.badgeNumber().strip()
                                : "VISIT-" + visit.id(),
                        visit.visitorName(), visit.hostName(), visit.checkedInAt(),
                        visit.accessZones() == null ? List.of() : visit.accessZones()))
                .toList();
    }
}
