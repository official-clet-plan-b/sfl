package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.SourceChannel;

/** Who is acting, and through which channel. Every S164 command and query carries one. */
public record Caller(ActorContext actor, SourceChannel channel) {

    public String id() {
        return actor.actorId();
    }

    public static Caller system(ActorContext actor) {
        return new Caller(actor, SourceChannel.SYSTEM);
    }
}
