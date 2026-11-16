package gh.edu.clet.sfl.common.features;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.common.features.SflSystemCatalogue.SystemDefinition;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SflFeatureGateTest {

    private static final SflSystemCatalogue CATALOGUE = new SflSystemCatalogue(
            List.of(
                    SystemDefinition.of("S166", "Fleet", List.of("/api/v1/fleet/**")),
                    SystemDefinition.of("S167", "Telematics", List.of("/api/v1/fleet/vehicles/tracking/**"), "S166"),
                    SystemDefinition.of("S168", "Fuel", List.of("/api/v1/fuel/**"), "S166"),
                    SystemDefinition.of("S168a", "VIP", List.of(), "S166"),
                    SystemDefinition.of("S171", "Dispatch", List.of("/api/v1/dispatch/**"))),
            List.of("/api/v1/system/**", "/api/v1/fleet/actor/**", "/api/v1/fleet/features"));

    @Test
    void blankSettingsSwitchEverythingOn() {
        SflFeatureGate gate = new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("", ""));
        assertThat(gate.snapshot()).containsOnlyKeys("S166", "S167", "S168", "S168a", "S171");
        assertThat(gate.snapshot().values()).allMatch(Boolean::booleanValue);
        assertThat(gate.allows("/api/v1/dispatch/items")).isTrue();
    }

    @Test
    void allowListSwitchesOffEverythingElse() {
        SflFeatureGate gate = new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("S166, s168A", ""));
        assertThat(gate.isEnabled("S168a")).isTrue();
        assertThat(gate.isEnabled("S171")).isFalse();
        assertThat(gate.allows("/api/v1/dispatch/items")).isFalse();
        assertThat(gate.allows("/api/v1/fuel/cards")).isFalse();
        assertThat(gate.allows("/api/v1/fleet/vehicles")).isTrue();
    }

    @Test
    void denyListWinsOverAllowList() {
        SflFeatureGate gate = new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("S166,S171", "S171"));
        assertThat(gate.isEnabled("S171")).isFalse();
    }

    @Test
    void mostSpecificPatternOwnsThePath() {
        SflFeatureGate gate = new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("S166", ""));
        assertThat(gate.systemFor("/api/v1/fleet/vehicles/tracking/live")).contains("S167");
        assertThat(gate.allows("/api/v1/fleet/vehicles/tracking/live")).isFalse();
    }

    @Test
    void anEnabledSystemKeepsItsDependencyApiOpenWithoutShowingIt() {
        SflFeatureGate gate = new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("S168", ""));
        assertThat(gate.isEnabled("S166")).isFalse();
        assertThat(gate.isApiOpen("S166")).isTrue();
        assertThat(gate.allows("/api/v1/fleet/vehicles")).isTrue();
        assertThat(gate.allows("/api/v1/dispatch/items")).isFalse();
    }

    @Test
    void alwaysOpenPathsAreNeverClosed() {
        SflFeatureGate gate = new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("S171", ""));
        assertThat(gate.allows("/api/v1/fleet/actor/permissions")).isTrue();
        assertThat(gate.allows("/api/v1/fleet/features")).isTrue();
        assertThat(gate.allows("/api/v1/system/info")).isTrue();
    }

    @Test
    void filterAnswersASwitchedOffSystemWith404() throws Exception {
        SflFeatureGateFilter filter = new SflFeatureGateFilter(
                new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("S166", "")));

        MockHttpServletResponse refused = new MockHttpServletResponse();
        MockFilterChain refusedChain = new MockFilterChain();
        MockHttpServletRequest refusedRequest = new MockHttpServletRequest("GET", "/api/v1/dispatch/items");
        // A caller-supplied header is never reflected into the body.
        refusedRequest.addHeader("X-Correlation-Id", "<script>alert(1)</script>");
        filter.doFilter(refusedRequest, refused, refusedChain);
        assertThat(refused.getStatus()).isEqualTo(404);
        assertThat(refused.getContentAsString()).contains(SflFeatureGateFilter.ERROR_CODE)
                .contains("switched off").doesNotContain("/api/v1/dispatch").doesNotContain("<script>");
        assertThat(refusedChain.getRequest()).isNull();

        MockHttpServletResponse served = new MockHttpServletResponse();
        MockFilterChain servedChain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/fleet/vehicles"), served, servedChain);
        assertThat(servedChain.getRequest()).isNotNull();
    }

    @Test
    void anAdministratorsChoiceOverridesTheDefaultImmediatelyAndCanBeReset() {
        SflFeatureFlagStore store = SflFeatureFlagStore.inMemory();
        SflFeatureGate gate = new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("S166", ""), store,
                java.time.Clock.systemUTC());
        assertThat(gate.allows("/api/v1/dispatch/items")).isFalse();

        gate.set("s171", true, "admin");
        assertThat(gate.isEnabled("S171")).isTrue();
        assertThat(gate.allows("/api/v1/dispatch/items")).isTrue();
        assertThat(store.load()).containsKey("S171");
        assertThat(gate.details()).filteredOn(s -> s.code().equals("S171"))
                .singleElement().satisfies(s -> {
                    assertThat(s.overridden()).isTrue();
                    assertThat(s.defaultEnabled()).isFalse();
                    assertThat(s.updatedBy()).isEqualTo("admin");
                });

        gate.set("S166", false, "admin");
        assertThat(gate.isEnabled("S166")).isFalse();

        gate.reset("S171", "admin");
        assertThat(gate.isEnabled("S171")).isFalse();
        assertThat(store.load()).doesNotContainKey("S171");
    }

    @Test
    void overridesWrittenElsewhereArePickedUpOnTheNextRefresh() {
        SflFeatureFlagStore store = SflFeatureFlagStore.inMemory();
        MutableClock clock = new MutableClock();
        SflFeatureGate gate = new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("", ""), store, clock);
        assertThat(gate.isEnabled("S171")).isTrue();

        store.save("S171", false, "other-replica", clock.instant());
        assertThat(gate.isEnabled("S171")).isTrue();
        clock.advance(SflFeatureGate.REFRESH_INTERVAL);
        assertThat(gate.isEnabled("S171")).isFalse();
    }

    @Test
    void onlyAdministratorsMayChangeFlags() {
        SflFeatureFlagsApi api = new SflFeatureFlagsApi(new SflFeatureGate(CATALOGUE, SflFeatureFlags.of("", "")));
        ActorContext manager = actor(SflRole.FLEET_MANAGER);
        ActorContext admin = actor(SflRole.SFL_ADMIN);

        assertThat(api.update("S171", new SflFeatureFlagsApi.UpdateRequest(false), manager).getStatusCode().value())
                .isEqualTo(403);
        assertThat(api.update("S999", new SflFeatureFlagsApi.UpdateRequest(false), admin).getStatusCode().value())
                .isEqualTo(404);
        assertThat(api.update("S171", new SflFeatureFlagsApi.UpdateRequest(null), admin).getStatusCode().value())
                .isEqualTo(400);
        var ok = api.update("S171", new SflFeatureFlagsApi.UpdateRequest(false), admin);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().data()).filteredOn(s -> s.code().equals("S171")).singleElement()
                .satisfies(s -> assertThat(s.enabled()).isFalse());
    }

    @Test
    void a_change_is_recorded_under_the_persons_name_and_their_subject() {
        ActorContext oidc = new ActorContext(new SiteScopedPrincipal("a648be83-05cb", "SFL Administrator",
                Set.of(SflRole.SFL_ADMIN), Set.of("*"), false), "c1");
        ActorContext header = new ActorContext(new SiteScopedPrincipal("dev.admin", null,
                Set.of(SflRole.SFL_ADMIN), Set.of("*"), false), "c1");

        assertThat(SflFeatureFlagsApi.changedBy(oidc)).isEqualTo("SFL Administrator (a648be83-05cb)");
        assertThat(SflFeatureFlagsApi.changedBy(header)).isEqualTo("dev.admin");
    }

    private static ActorContext actor(SflRole role) {
        return new ActorContext(new SiteScopedPrincipal("u1", "User", Set.of(role), Set.of("*"), false), "c1");
    }

    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant now = java.time.Instant.parse("2026-11-16T10:00:00Z");

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }
}
