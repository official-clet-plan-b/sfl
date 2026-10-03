package gh.edu.clet.sfl.safetysecurity.accesscontrol.application.service;

import gh.edu.clet.sfl.safetysecurity.accesscontrol.application.contract.AccessZoneDirectory;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.application.port.AccessControlRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** S160a's answer to {@link AccessZoneDirectory}: a read of the zone register, no side effects and no authority beyond the caller's own site scope. */
@Service
public class AccessZoneDirectoryService implements AccessZoneDirectory {

    private final AccessControlRepository repository;

    public AccessZoneDirectoryService(AccessControlRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ZoneRef> find(UUID zoneId, String siteCode) {
        return repository.findZone(zoneId).filter(z -> z.siteCode().equalsIgnoreCase(siteCode))
                .map(z -> new ZoneRef(z.id(), z.siteCode(), z.zoneCode(), z.name()));
    }
}
