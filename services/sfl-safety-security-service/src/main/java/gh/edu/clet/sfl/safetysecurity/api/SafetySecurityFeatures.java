package gh.edu.clet.sfl.safetysecurity.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.features.SflFeatureFlagsApi;
import gh.edu.clet.sfl.common.features.SflFeatureGate;
import gh.edu.clet.sfl.common.features.SflSystemCatalogue;
import gh.edu.clet.sfl.common.features.SflSystemCatalogue.SystemDefinition;
import gh.edu.clet.sfl.safetysecurity.emergency.api.EmergencyActorResolver;
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
 * The SSEMP systems this service hosts, for feature flags.
 *
 * <p>Permit to work (S164) links a current risk assessment, so it depends on S165.
 */
@Configuration(proxyBeanMethods = false)
public class SafetySecurityFeatures {

    public static final String FEATURES_PATH = "/api/v1/emergency/features";

    @Bean
    SflSystemCatalogue safetySecuritySystemCatalogue() {
        return new SflSystemCatalogue(
                List.of(
                        SystemDefinition.of("S160", "Visitor management", List.of("/api/v1/visitors/**")),
                        SystemDefinition.of("S160a", "Physical access control", List.of("/api/v1/access-control/**")),
                        SystemDefinition.of("S161", "CCTV / VMS", List.of("/api/v1/cctv/**")),
                        SystemDefinition.of("S162", "Intrusion & alarms", List.of("/api/v1/intrusion/**")),
                        SystemDefinition.of("S162a", "Fire & life safety", List.of("/api/v1/life-safety/**")),
                        SystemDefinition.of("S163", "HSE incidents & near misses", List.of("/api/v1/incidents/**")),
                        SystemDefinition.of("S164", "Permit to work",
                                List.of("/api/v1/permits/**", "/api/v1/permit-types/**"), "S165"),
                        SystemDefinition.of("S165", "Risk assessment library", List.of("/api/v1/risk-assessments/**")),
                        SystemDefinition.of("S174", "Emergency mass notification", List.of("/api/v1/emergency/**")),
                        SystemDefinition.of("S175", "Crisis & evacuation drills", List.of("/api/v1/drills/**"))),
                List.of("/api/v1/system/**", "/api/v1/emergency/actor/**", FEATURES_PATH, FEATURES_PATH + "/**"));
    }

    @RestController
    @io.swagger.v3.oas.annotations.tags.Tag(name = "Feature Flags")
    static class FeaturesController {

        private final SflFeatureFlagsApi api;
        private final EmergencyActorResolver actors;

        FeaturesController(SflFeatureFlagsApi api, EmergencyActorResolver actors) {
            this.api = api;
            this.actors = actors;
        }

        @io.swagger.v3.oas.annotations.Operation(summary = "Which SSEMP systems are switched on in this deployment")
        @GetMapping(FEATURES_PATH)
        ApiResponse<List<SflFeatureGate.SystemState>> features() {
            return api.list();
        }

        @io.swagger.v3.oas.annotations.Operation(summary = "Switches a SSEMP system on or off immediately (platform administrators only)")
        @PutMapping(FEATURES_PATH + "/{systemCode}")
        ResponseEntity<ApiResponse<List<SflFeatureGate.SystemState>>> update(@PathVariable String systemCode,
                @RequestBody(required = false) SflFeatureFlagsApi.UpdateRequest request, HttpServletRequest httpRequest) {
            return api.update(systemCode, request, actors.resolve(httpRequest));
        }

        @io.swagger.v3.oas.annotations.Operation(summary = "Returns a SSEMP system to its deployment default (platform administrators only)")
        @DeleteMapping(FEATURES_PATH + "/{systemCode}")
        ResponseEntity<ApiResponse<List<SflFeatureGate.SystemState>>> reset(@PathVariable String systemCode,
                HttpServletRequest httpRequest) {
            return api.reset(systemCode, actors.resolve(httpRequest));
        }
    }
}
