package gh.edu.clet.sfl.facilities.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.features.SflFeatureFlagsApi;
import gh.edu.clet.sfl.common.features.SflFeatureGate;
import gh.edu.clet.sfl.common.features.SflSystemCatalogue;
import gh.edu.clet.sfl.common.features.SflSystemCatalogue.SystemDefinition;
import gh.edu.clet.sfl.facilities.shared.api.FacilitiesActorResolver;
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
 * The IFIMP systems this service hosts, for feature flags.
 *
 * <p>Every IFIMP system places its work on the estate (sites, buildings, spaces, assets), which S152
 * owns, so each depends on S152: its API stays reachable while any of them is switched on.
 */
@Configuration(proxyBeanMethods = false)
public class FacilitiesFeatures {

    public static final String FEATURES_PATH = "/api/v1/facilities/features";

    private static final String ESTATE = "S152";

    @Bean
    SflSystemCatalogue facilitiesSystemCatalogue() {
        return new SflSystemCatalogue(
                List.of(
                        SystemDefinition.of(ESTATE, "Facility management", List.of("/api/v1/facilities/**")),
                        SystemDefinition.of("S153", "Maintenance management", List.of(
                                "/api/v1/facilities/faults/**", "/api/v1/facilities/maintenance/**",
                                "/api/v1/facilities/maintenance-evidence/**", "/api/v1/facilities/work-orders/**"), ESTATE),
                        SystemDefinition.of("S156", "Building systems & IoT",
                                List.of("/api/v1/facilities/building-systems/**"), ESTATE),
                        SystemDefinition.of("S157", "Energy & sustainability", List.of("/api/v1/facilities/energy/**"), ESTATE),
                        SystemDefinition.of("S158", "Space planning & moves",
                                List.of("/api/v1/facilities/space-planning/**"), ESTATE),
                        SystemDefinition.of("S159", "Room & resource booking", List.of(
                                "/api/v1/facilities/bookable-resources/**", "/api/v1/facilities/booking-availability/**",
                                "/api/v1/facilities/bookings/**", "/api/v1/facilities/setup-tasks/**"), ESTATE),
                        SystemDefinition.of("S169", "Cleaning operations", List.of("/api/v1/facilities/cleaning/**"), ESTATE),
                        SystemDefinition.of("S170", "Hygiene & pest control", List.of("/api/v1/facilities/hygiene/**"), ESTATE),
                        SystemDefinition.of("S172", "Catering & cafeteria", List.of("/api/v1/facilities/catering/**"), ESTATE),
                        SystemDefinition.of("S173", "Event logistics", List.of("/api/v1/facilities/event-logistics/**"), ESTATE),
                        SystemDefinition.of("S176", "Construction projects",
                                List.of("/api/v1/facilities/construction/**"), ESTATE),
                        SystemDefinition.of("S177", "Lease & tenancy", List.of("/api/v1/facilities/leases/**"), ESTATE),
                        SystemDefinition.of("S178", "Waste & recycling", List.of("/api/v1/facilities/waste/**"), ESTATE),
                        SystemDefinition.of("S179", "Lost & found", List.of("/api/v1/facilities/lost-found/**"), ESTATE)),
                List.of("/api/v1/system/**", "/api/v1/facilities/actor/**", FEATURES_PATH, FEATURES_PATH + "/**"));
    }

    @RestController
    @io.swagger.v3.oas.annotations.tags.Tag(name = "Feature Flags")
    static class FeaturesController {

        private final SflFeatureFlagsApi api;
        private final FacilitiesActorResolver actors;

        FeaturesController(SflFeatureFlagsApi api, FacilitiesActorResolver actors) {
            this.api = api;
            this.actors = actors;
        }

        @io.swagger.v3.oas.annotations.Operation(summary = "Which IFIMP systems are switched on in this deployment")
        @GetMapping(FEATURES_PATH)
        ApiResponse<List<SflFeatureGate.SystemState>> features() {
            return api.list();
        }

        @io.swagger.v3.oas.annotations.Operation(summary = "Switches a IFIMP system on or off immediately (platform administrators only)")
        @PutMapping(FEATURES_PATH + "/{systemCode}")
        ResponseEntity<ApiResponse<List<SflFeatureGate.SystemState>>> update(@PathVariable String systemCode,
                @RequestBody(required = false) SflFeatureFlagsApi.UpdateRequest request, HttpServletRequest httpRequest) {
            return api.update(systemCode, request, actors.resolve(httpRequest));
        }

        @io.swagger.v3.oas.annotations.Operation(summary = "Returns a IFIMP system to its deployment default (platform administrators only)")
        @DeleteMapping(FEATURES_PATH + "/{systemCode}")
        ResponseEntity<ApiResponse<List<SflFeatureGate.SystemState>>> reset(@PathVariable String systemCode,
                HttpServletRequest httpRequest) {
            return api.reset(systemCode, actors.resolve(httpRequest));
        }
    }
}
