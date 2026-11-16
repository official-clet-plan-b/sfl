package gh.edu.clet.sfl.fleetlogistics.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.features.SflFeatureFlagsApi;
import gh.edu.clet.sfl.common.features.SflFeatureGate;
import gh.edu.clet.sfl.common.features.SflSystemCatalogue;
import gh.edu.clet.sfl.common.features.SflSystemCatalogue.SystemDefinition;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.FleetActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The FTLMP systems this service hosts, for feature flags.
 *
 * <p>S168a (VIP trip booking) has no API of its own - it books through the trip register - so it
 * owns no paths and depends on S166. Switching it off hides its screens only.
 */
@Configuration(proxyBeanMethods = false)
public class FleetLogisticsFeatures {

    public static final String FEATURES_PATH = "/api/v1/fleet/features";

    @Bean
    SflSystemCatalogue fleetLogisticsSystemCatalogue() {
        return new SflSystemCatalogue(
                List.of(
                        SystemDefinition.of("S166", "Fleet & vehicle management", List.of("/api/v1/fleet/**")),
                        SystemDefinition.of("S167", "GPS & telematics",
                                List.of("/api/v1/fleet/vehicles/tracking/**", "/api/v1/fleet/vehicles/tracking"), "S166"),
                        SystemDefinition.of("S168", "Fuel & driver logbooks", List.of("/api/v1/fuel/**"), "S166"),
                        SystemDefinition.of("S168a", "VIP trip booking", List.of(), "S166"),
                        SystemDefinition.of("AVAMP", "Asset tagging & inventory", List.of("/api/v1/assets/**")),
                        SystemDefinition.of("S171", "Courier & dispatch", List.of("/api/v1/dispatch/**"))),
                List.of("/api/v1/system/**", "/api/v1/fleet/actor/**", FEATURES_PATH, FEATURES_PATH + "/**"));
    }

    @RestController
    @io.swagger.v3.oas.annotations.tags.Tag(name = "Feature Flags")
    static class FeaturesController {

        private final SflFeatureFlagsApi api;
        private final FleetActorResolver actors;

        FeaturesController(SflFeatureFlagsApi api, FleetActorResolver actors) {
            this.api = api;
            this.actors = actors;
        }

        @io.swagger.v3.oas.annotations.Operation(summary = "Which FTLMP systems are switched on in this deployment")
        @GetMapping(FEATURES_PATH)
        ApiResponse<List<SflFeatureGate.SystemState>> features() {
            return api.list();
        }

        @io.swagger.v3.oas.annotations.Operation(summary = "Switches a FTLMP system on or off immediately (platform administrators only)")
        @PutMapping(FEATURES_PATH + "/{systemCode}")
        ResponseEntity<ApiResponse<List<SflFeatureGate.SystemState>>> update(@PathVariable String systemCode,
                @RequestBody(required = false) SflFeatureFlagsApi.UpdateRequest request, HttpServletRequest httpRequest) {
            return api.update(systemCode, request, actors.resolve(httpRequest));
        }

        @io.swagger.v3.oas.annotations.Operation(summary = "Returns a FTLMP system to its deployment default (platform administrators only)")
        @DeleteMapping(FEATURES_PATH + "/{systemCode}")
        ResponseEntity<ApiResponse<List<SflFeatureGate.SystemState>>> reset(@PathVariable String systemCode,
                HttpServletRequest httpRequest) {
            return api.reset(systemCode, actors.resolve(httpRequest));
        }
    }
}
