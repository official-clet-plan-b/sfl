package gh.edu.clet.sfl.facilities.shared.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;

/** Who is asking, and through what channel: travels with every command and query of the estate modules. */
public record Caller(ActorContext actor, SourceChannel channel) {
}
