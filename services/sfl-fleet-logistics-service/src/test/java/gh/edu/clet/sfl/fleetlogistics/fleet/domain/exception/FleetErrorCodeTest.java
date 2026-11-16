package gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SRS <em>Error States</em> wording is contract. If a message here is reworded the SRS has changed
 * or the implementation has drifted - either way this test must fail rather than let the drift ship.
 *
 * <p>Traces: SRS-SFL-S166-01, -02, -03, -04, -05 error states.
 */
class FleetErrorCodeTest {

    private static final Map<FleetErrorCode, String> SRS_WORDING = Map.ofEntries(
            // SRS-SFL-S166-01
            Map.entry(FleetErrorCode.FLEET_DUPLICATE_IDENTIFIER,
                    "An active record with this identifier already exists for this site."),
            Map.entry(FleetErrorCode.FLEET_MISSING_SITE_SCOPE,
                    "Select a valid GSL site before saving this record."),
            Map.entry(FleetErrorCode.FLEET_UNAUTHORIZED_SCOPE,
                    "You are not authorised to access this site or record."),
            // SRS-SFL-S166-02
            Map.entry(FleetErrorCode.FLEET_CLOSURE_EVIDENCE_MISSING,
                    "Required evidence must be attached before closure."),
            Map.entry(FleetErrorCode.FLEET_SLA_BREACH,
                    "This item has breached its configured SLA and has been escalated."),
            Map.entry(FleetErrorCode.FLEET_UNAUTHORIZED_APPROVAL,
                    "You do not have permission to approve this workflow transition."),
            // SRS-SFL-S166-03
            Map.entry(FleetErrorCode.FLEET_EXPORT_NOT_APPROVED,
                    "Evidence export requires approval and a recorded reason."),
            Map.entry(FleetErrorCode.FLEET_RETENTION_CLASS_MISSING,
                    "Select a retention class before saving this evidence."),
            Map.entry(FleetErrorCode.FLEET_AUDIT_CHAIN_FAILURE,
                    "Audit integrity check failed. Escalate to compliance and security."),
            // SRS-SFL-S166-04
            Map.entry(FleetErrorCode.FLEET_INTEGRATION_INVALID_SIGNATURE,
                    "Integration message rejected: signature verification failed."),
            Map.entry(FleetErrorCode.FLEET_INTEGRATION_SCHEMA_INVALID,
                    "Integration message rejected: payload does not match registered schema."),
            Map.entry(FleetErrorCode.FLEET_INTEGRATION_DUPLICATE_MESSAGE,
                    "Duplicate integration message received and safely ignored."),
            // SRS-SFL-S166-05
            Map.entry(FleetErrorCode.FLEET_DASHBOARD_DATA_STALE,
                    "Dashboard data is older than the configured freshness threshold."),
            Map.entry(FleetErrorCode.FLEET_DASHBOARD_NO_SCOPE,
                    "No site scope is assigned to your user profile."),
            Map.entry(FleetErrorCode.FLEET_DASHBOARD_RESTRICTED_DRILLDOWN,
                    "You do not have permission to view the underlying record."));

    @Test
    @DisplayName("every SRS-defined code reproduces the SRS Error States wording verbatim")
    void srs_defined_codes_carry_the_srs_wording() {
        SRS_WORDING.forEach((code, expected) -> assertThat(code.message())
                .as("SRS wording for %s", code)
                .isEqualTo(expected));
    }

    @Test
    @DisplayName("exactly the SRS error states are flagged as SRS-defined")
    void srs_defined_flag_matches_the_srs_error_state_list() {
        var flagged = java.util.Arrays.stream(FleetErrorCode.values())
                .filter(FleetErrorCode::srsDefined)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(flagged).containsExactlyInAnyOrderElementsOf(SRS_WORDING.keySet());
    }

    @Test
    @DisplayName("the machine-readable code is the constant name")
    void code_is_the_constant_name() {
        for (FleetErrorCode code : FleetErrorCode.values()) {
            assertThat(code.code()).isEqualTo(code.name());
            assertThat(code.message()).isNotBlank();
        }
    }
}
