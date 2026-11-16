package gh.edu.clet.sfl.fleetlogistics.fleet.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.MissingSiteScopeException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Traces: SRS-SFL-S166-01 system-managed fields and mandatory site scope. */
class FleetValueObjectTest {

    @Nested
    @DisplayName("SiteCode")
    class SiteCodeTest {

        @Test
        @DisplayName("normalises to upper case so site comparisons are case-insensitive")
        void normalises_to_upper_case() {
            assertThat(SiteCode.of(" accra ").value()).isEqualTo("ACCRA");
        }

        @Test
        @DisplayName("a blank site raises the SRS Missing Site Scope error")
        void blank_site_is_rejected() {
            assertThatThrownBy(() -> SiteCode.of("  "))
                    .isInstanceOf(MissingSiteScopeException.class)
                    .hasMessage("Select a valid GSL site before saving this record.");
            assertThatThrownBy(() -> SiteCode.of(null)).isInstanceOf(MissingSiteScopeException.class);
        }
    }

    @Nested
    @DisplayName("DateTimeRange")
    class DateTimeRangeTest {

        private final Instant nine = Instant.parse("2026-07-21T09:00:00Z");
        private final Instant twelve = Instant.parse("2026-07-21T12:00:00Z");
        private final Instant fifteen = Instant.parse("2026-07-21T15:00:00Z");

        @Test
        @DisplayName("requires a positive duration")
        void requires_a_positive_duration() {
            assertThatThrownBy(() -> DateTimeRange.of(twelve, nine))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("end must be after start");
            assertThatThrownBy(() -> DateTimeRange.of(nine, nine))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("overlapping periods are detected in both directions")
        void detects_overlap() {
            DateTimeRange morning = DateTimeRange.of(nine, twelve);
            DateTimeRange overlapping = DateTimeRange.of(Instant.parse("2026-07-21T11:00:00Z"), fifteen);

            assertThat(morning.overlaps(overlapping)).isTrue();
            assertThat(overlapping.overlaps(morning)).isTrue();
        }

        @Test
        @DisplayName("back-to-back periods do not overlap, so a vehicle can turn around at the boundary")
        void back_to_back_periods_do_not_overlap() {
            DateTimeRange morning = DateTimeRange.of(nine, twelve);
            DateTimeRange afternoon = DateTimeRange.of(twelve, fifteen);

            assertThat(morning.overlaps(afternoon)).isFalse();
            assertThat(afternoon.overlaps(morning)).isFalse();
        }

        @Test
        @DisplayName("containment is half-open: the start is inside, the end is not")
        void containment_is_half_open() {
            DateTimeRange morning = DateTimeRange.of(nine, twelve);

            assertThat(morning.contains(nine)).isTrue();
            assertThat(morning.contains(twelve)).isFalse();
        }
    }

    @Nested
    @DisplayName("RecordMetadata")
    class RecordMetadataTest {

        private final Instant created = Instant.parse("2026-07-21T08:00:00Z");
        private final Instant modified = Instant.parse("2026-07-21T09:30:00Z");

        @Test
        @DisplayName("a new record records the creating actor, channel and correlation id at version zero")
        void creation_populates_the_system_managed_fields() {
            RecordMetadata metadata = RecordMetadata.createdBy("officer@clet.edu.gh", created, SourceChannel.WEB,
                    "corr-1");

            assertThat(metadata.createdBy()).isEqualTo("officer@clet.edu.gh");
            assertThat(metadata.lastModifiedBy()).isEqualTo("officer@clet.edu.gh");
            assertThat(metadata.createdAt()).isEqualTo(created);
            assertThat(metadata.version()).isZero();
            assertThat(metadata.sourceChannel()).isEqualTo(SourceChannel.WEB);
            assertThat(metadata.auditCorrelationId()).isEqualTo("corr-1");
        }

        @Test
        @DisplayName("an update keeps the creation fields and replaces the modification fields")
        void update_preserves_creation_fields() {
            RecordMetadata updated = RecordMetadata
                    .createdBy("officer@clet.edu.gh", created, SourceChannel.WEB, "corr-1")
                    .modifiedBy("manager@clet.edu.gh", modified, SourceChannel.MOBILE, "corr-2");

            assertThat(updated.createdBy()).isEqualTo("officer@clet.edu.gh");
            assertThat(updated.createdAt()).isEqualTo(created);
            assertThat(updated.lastModifiedBy()).isEqualTo("manager@clet.edu.gh");
            assertThat(updated.lastModifiedAt()).isEqualTo(modified);
            assertThat(updated.sourceChannel()).isEqualTo(SourceChannel.MOBILE);
        }

        @Test
        @DisplayName("modification cannot precede creation")
        void modification_cannot_precede_creation() {
            assertThatThrownBy(() -> RecordMetadata.rehydrate("officer", modified, "officer", created, 1L,
                    SourceChannel.WEB, "corr-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot precede");
        }

        @Test
        @DisplayName("required fields are enforced")
        void required_fields_are_enforced() {
            assertThatThrownBy(() -> RecordMetadata.createdBy("  ", created, SourceChannel.WEB, "corr-1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RecordMetadata.createdBy("officer", created, null, "corr-1"))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
