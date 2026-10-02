package gh.edu.clet.sfl.fleetlogistics.assets.application;

import java.util.UUID;

public record AssignTagCommand(UUID assetId, String tagId, String actor, String correlationId) {
}
