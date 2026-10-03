package gh.edu.clet.sfl.facilities.lostfound.infrastructure.integration;

import gh.edu.clet.sfl.facilities.lostfound.application.ports.LostFoundEstatePort;
import gh.edu.clet.sfl.facilities.masterdata.application.ports.FacilitiesRepository;
import org.springframework.stereotype.Component;

/** S179's read of the S152 register - the one class in S179 that names S152. */
@Component
public class S152LostFoundEstateAdapter implements LostFoundEstatePort {

    private final FacilitiesRepository facilities;

    public S152LostFoundEstateAdapter(FacilitiesRepository facilities) {
        this.facilities = facilities;
    }

    @Override
    public boolean siteExists(String siteCode) {
        return facilities.findSiteByCode(siteCode).isPresent();
    }
}
