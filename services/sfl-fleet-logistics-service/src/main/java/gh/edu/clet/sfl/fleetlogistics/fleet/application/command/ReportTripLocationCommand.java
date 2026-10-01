package gh.edu.clet.sfl.fleetlogistics.fleet.application.command;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SourceChannel;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The assigned driver reports where the vehicle is right now, while the trip is in progress.
 *
 * <p>No idempotency key: a position report is not a state-creating request in the sense the rest of
 * this interface's default exists for - a duplicate ping a moment apart is not a retry to be
 * collapsed, it is the next, equally true reading. There is nothing to replay.
 */
public record ReportTripLocationCommand(
        UUID tripId,
        BigDecimal latitude,
        BigDecimal longitude,
        ActorContext actor,
        SourceChannel sourceChannel) implements FleetCommand {
}
