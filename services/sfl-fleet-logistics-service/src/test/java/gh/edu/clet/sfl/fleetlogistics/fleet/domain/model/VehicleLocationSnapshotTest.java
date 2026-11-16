package gh.edu.clet.sfl.fleetlogistics.fleet.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleLocationSnapshotTest {

    private static VehicleLocationSnapshot snapshot(String source, UUID messageId) {
        return new VehicleLocationSnapshot(UUID.randomUUID(), UUID.randomUUID(), SiteCode.of("CLET-HQ"),
                new BigDecimal("5.55"), new BigDecimal("-0.20"), null, Instant.parse("2026-11-16T10:00:00Z"),
                source, messageId, "corr");
    }

    @Test
    void a_driver_reported_position_needs_no_inbox_message() {
        VehicleLocationSnapshot location = snapshot(VehicleLocationSnapshot.DRIVER_MOBILE, null);

        assertThat(location.integrationMessageId()).isNull();
        assertThat(location.auditImage()).containsEntry("integrationMessageId", "");
    }

    @Test
    void an_integration_fed_position_must_name_its_inbox_message() {
        assertThatThrownBy(() -> snapshot("ACME-TRACKER", null)).isInstanceOf(NullPointerException.class);
        assertThat(snapshot("ACME-TRACKER", UUID.randomUUID()).integrationMessageId()).isNotNull();
    }
}
