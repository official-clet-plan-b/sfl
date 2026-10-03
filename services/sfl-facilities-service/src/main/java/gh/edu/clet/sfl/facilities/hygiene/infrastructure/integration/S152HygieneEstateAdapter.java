package gh.edu.clet.sfl.facilities.hygiene.infrastructure.integration;

import gh.edu.clet.sfl.facilities.hygiene.application.ports.HygieneEstatePort;
import gh.edu.clet.sfl.facilities.masterdata.application.ports.FacilitiesRepository;
import gh.edu.clet.sfl.facilities.masterdata.domain.FacilityRoom;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** S170's read of the S152 register - the one class in S170 that names S152. */
@Component
public class S152HygieneEstateAdapter implements HygieneEstatePort {

    private final FacilitiesRepository facilities;

    public S152HygieneEstateAdapter(FacilitiesRepository facilities) {
        this.facilities = facilities;
    }

    @Override
    public boolean siteExists(String siteCode) {
        return facilities.findSiteByCode(siteCode).isPresent();
    }

    @Override
    public Optional<String> siteOfRoom(UUID roomId) {
        return facilities.findRoom(roomId).map(FacilityRoom::siteCode);
    }
}
