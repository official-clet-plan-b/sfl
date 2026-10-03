package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a drill is meant to be - SRS-SFL-S175-01: "site, type (fire, security lockdown, medical, combined),
 * scenario and expected participants" - plus what S175-02 and S175-05 need to run and judge it.
 *
 * @param expectedParticipants who the drill is for, in words ("all Block A staff and visitors"); the measured
 *        baseline is taken from live occupancy at drill start, not from this
 * @param assemblyZone the S162a muster point the roll-call is taken at
 * @param notificationTemplateId the S174 drill template; required before the drill is scheduled
 */
public record DrillPlan(DrillType drillType, String title, String scenario, String expectedParticipants,
        String assemblyZone, Instant scheduledFor, UUID notificationTemplateId, List<UUID> audienceGroupIds,
        List<UUID> recipientZoneIds, List<ModuleExpectation> expectations) {

    public DrillPlan {
        Objects.requireNonNull(drillType, "drillType is required");
        title = blankToNull(title);
        if (title == null) {
            throw new IllegalArgumentException("title is required");
        }
        scenario = blankToNull(scenario);
        expectedParticipants = blankToNull(expectedParticipants);
        assemblyZone = assemblyZone == null || assemblyZone.isBlank() ? null : assemblyZone.strip();
        audienceGroupIds = audienceGroupIds == null ? List.of() : List.copyOf(audienceGroupIds);
        recipientZoneIds = recipientZoneIds == null ? List.of() : List.copyOf(recipientZoneIds);
        expectations = expectations == null ? List.of() : List.copyOf(expectations);
    }

    public DrillPlan withExpectations(List<ModuleExpectation> next) {
        return new DrillPlan(drillType, title, scenario, expectedParticipants, assemblyZone, scheduledFor,
                notificationTemplateId, audienceGroupIds, recipientZoneIds, next);
    }

    public DrillPlan rescheduledFor(Instant when) {
        return new DrillPlan(drillType, title, scenario, expectedParticipants, assemblyZone, when,
                notificationTemplateId, audienceGroupIds, recipientZoneIds, expectations);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
