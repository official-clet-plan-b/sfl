package gh.edu.clet.sfl.facilities.waste.infrastructure.integration;

import gh.edu.clet.sfl.facilities.masterdata.application.ports.FacilitiesRepository;
import gh.edu.clet.sfl.facilities.masterdata.domain.FacilityRoom;
import gh.edu.clet.sfl.facilities.waste.application.ports.WasteEstatePort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** S178's read of the S152 register - the one class in S178 that names S152. */
@Component
public class S152WasteEstateAdapter implements WasteEstatePort {

    private final FacilitiesRepository facilities;

    public S152WasteEstateAdapter(FacilitiesRepository facilities) {
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
