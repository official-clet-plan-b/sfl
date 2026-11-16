package gh.edu.clet.sfl.fleetlogistics.fleet.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.DriverProfileRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverProfileReference;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a signed-in actor into the set of driver records they may see.
 *
 * <h2>The join this replaces</h2>
 *
 * <p>Three places in this service asked "is this record the actor's own?" by comparing a driver's
 * {@code staffReference} against {@code ActorContext.actorId()}. That holds under header
 * authentication, where the caller supplies {@code X-SFL-User} and it happens to be the staff
 * reference. It cannot hold under a token, where {@code actorId()} is the subject claim issued by the
 * platform's OIDC provider (Zitadel). So from the day authentication was switched on, the comparison
 * was false for every driver:
 * the narrowing refused drivers their own records and, because the collection query had no narrowing
 * at all, still showed them everybody's.
 *
 * <p>The join is now an explicit column, {@code driver_profile_references.principal_subject}, resolved
 * here once per request.
 *
 * <h2>Who gets narrowed</h2>
 *
 * <p>Derived from the permission, not from a list of role names: an actor is narrowed when they hold
 * no supervising permission for the thing being read. {@code FLEET_TRIP_MANAGE} is the supervising
 * permission for trips, {@code FUEL_TRANSACTION_MANAGE} for fuel. A hard-coded list of "supervisor
 * roles" would have to be found and edited every time the matrix gains a role - and the failure mode
 * of forgetting is that a new supervisory role silently sees only their own records, or worse, that a
 * new limited role silently sees everything.
 *
 * <p>Note the direction of the default: an actor holding the supervising permission is
 * {@link DriverScope.Everything}, and everyone else who is not bound to a driver profile is
 * {@link DriverScope.Nothing} - not "unnarrowed".
 */
@Component
public class DriverScopeResolver {

    private final DriverProfileRepository driverProfiles;
    private final FleetAccessPolicy accessPolicy;

    public DriverScopeResolver(DriverProfileRepository driverProfiles, FleetAccessPolicy accessPolicy) {
        this.driverProfiles = driverProfiles;
        this.accessPolicy = accessPolicy;
    }

    /**
     * The scope for an actor reading records supervised by {@code supervisingPermission}.
     *
     * <p>Holding the supervising permission short-circuits the lookup: a fleet manager who also has a
     * driver profile is a fleet manager here, and reading their binding first would narrow them to
     * their own trips the moment somebody bound them.
     */
    @Transactional(readOnly = true)
    public DriverScope resolve(ActorContext actor, SflPermission supervisingPermission) {
        return resolve(actor, !accessPolicy.has(actor, supervisingPermission));
    }

    /**
     * The same resolution, for a caller that decides "is this actor narrowed" by its own rule.
     *
     * <p>The fuel module already answers that question with {@code FuelAccessPolicy.isDriverOnly},
     * against its own permission matrix. Passing the answer in rather than re-deriving it here keeps
     * one definition of who is narrowed per module and one definition of how a driver is identified,
     * which is the split that matters: the second is the part that was wrong everywhere.
     */
    @Transactional(readOnly = true)
    public DriverScope resolve(ActorContext actor, boolean narrowed) {
        if (!narrowed) {
            return new DriverScope.Everything();
        }

        // Linked either to the sign-in's stable subject or to the username the person signs in with -
        // the one a fleet administrator actually knows and types when registering a driver.
        Optional<DriverProfileReference> profile =
                driverProfiles.findActiveByPrincipalSubject(actor.actorId());
        String loginName = actor.principal().loginName();
        if (profile.isEmpty() && loginName != null) {
            profile = driverProfiles.findActiveByPrincipalSubject(loginName)
                    .or(() -> driverProfiles.findActiveByPrincipalSubject(loginName.toLowerCase(Locale.ROOT)));
        }
        if (profile.isEmpty()) {
            return new DriverScope.Nothing(
                    "Your sign-in is not linked to a driver profile, so no driver records are shown. "
                            + "Ask fleet administration to link your profile.");
        }

        DriverProfileReference driver = profile.get();
        return new DriverScope.Own(driver.id(), driver.staffReference());
    }
}
