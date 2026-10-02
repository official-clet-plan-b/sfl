package gh.edu.clet.sfl.fleetlogistics.assets.domain.policy;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.common.security.SflRole;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which role may do what in AVAMP-Lite.
 *
 * <h2>Why this file did not exist until 1 August 2026</h2>
 *
 * It should have. Until this was written the service had **no authorisation of any kind** - no
 * permission matrix, no policy, not one {@code require} call across eight endpoints. Any
 * authenticated caller could register an asset, read the whole register and query assets by location
 * at any site, and that included every one of the twenty-two seeded accounts down to the driver.
 *
 * The reason it went unnoticed is worth recording, because it is a pattern rather than an oversight.
 * A1 fixed how this service establishes *identity*: it used to take the actor from a caller-supplied
 * {@code X-SFL-User} header defaulting to {@code development-user}, and it now resolves the JWT
 * subject. That work was real and was reported as done. Nobody then asked what the service did with
 * the identity it had gone to the trouble of establishing. The answer was nothing at all.
 *
 * <h2>The two permissions, and why AVAMP does not borrow</h2>
 *
 * {@link SflPermission#ASSET_REFERENCE_READ} and {@link SflPermission#ASSET_REFERENCE_MANAGE} are the
 * whole vocabulary, which suits a register whose operations are read, register, move, assign custody
 * and link evidence.
 *
 * They are AVAMP's own and deliberately not shared with S152's {@code FACILITIES_ASSET_*}. The two
 * registers describe different things: an S152 facility asset is fixed plant whose condition feeds a
 * space's readiness, while an AVAMP asset reference is a tracked object that moves between sites and
 * custodians. A facilities manager holding {@code FACILITIES_ASSET_MANAGE} has no business editing
 * the movable-asset register by side effect, and a grant satisfiable by an IFIMP role would let
 * exactly that happen.
 *
 * <h2>Who gets what</h2>
 *
 * <ul>
 *   <li><strong>Read</strong> is broad, because knowing where a tracked device is helps everyone who
 *       operates the estate, and the register carries no personal data - an asset code, a category, a
 *       location and a custodian reference.</li>
 *   <li><strong>Manage</strong> is narrow. Moving an asset or reassigning custody rewrites the chain
 *       of responsibility for a physical object, which is an administrative and integration act
 *       rather than an operational convenience. The one operational exception is the fleet office
 *       ({@link SflRole#FLEET_MANAGER}, {@link SflRole#FLEET_LOGISTICS_OFFICER}): the asset tagging
 *       screen is part of their programme and they are the people who tag, move and hand over the
 *       vehicles and equipment in it, so withholding the grant left them a register of buttons that
 *       all answered 403. {@link SflRole#FLEET_REPORTING_VIEWER} stays out entirely.</li>
 *   <li>{@link SflRole#FLEET_DRIVER}, {@link SflRole#IFIMP_REQUESTER} and
 *       {@link SflRole#VENDOR_TECHNICIAN} appear nowhere. A contractor is not CLET staff, a requester
 *       books rooms, and a driver drives - none of the three has a reason to enumerate the estate's
 *       tracked devices, and before this file all three could.</li>
 * </ul>
 */
public final class AssetVisibilityPermissionMatrix {

    private static final Set<SflPermission> READ_ONLY = EnumSet.of(SflPermission.ASSET_REFERENCE_READ);

    private static final Set<SflPermission> READ_AND_MANAGE =
            EnumSet.of(SflPermission.ASSET_REFERENCE_READ, SflPermission.ASSET_REFERENCE_MANAGE);

    private static final Map<SflRole, Set<SflPermission>> MATRIX = buildMatrix();

    private AssetVisibilityPermissionMatrix() {
    }

    public static Set<SflPermission> permissionsFor(SflRole role) {
        return MATRIX.getOrDefault(role, Set.of());
    }

    /** The union of permissions granted by all of an actor's roles. */
    public static Set<SflPermission> permissionsFor(Set<SflRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        EnumSet<SflPermission> granted = EnumSet.noneOf(SflPermission.class);
        roles.forEach(role -> granted.addAll(permissionsFor(role)));
        return Set.copyOf(granted);
    }

    public static boolean grants(Set<SflRole> roles, SflPermission permission) {
        if (roles == null || permission == null) {
            return false;
        }
        return roles.stream().anyMatch(role -> permissionsFor(role).contains(permission));
    }

    private static Map<SflRole, Set<SflPermission>> buildMatrix() {
        Map<SflRole, Set<SflPermission>> matrix = new EnumMap<>(SflRole.class);

        // Platform administration. SFL_ADMIN is the business/system administrator and holds the full
        // read-and-manage grant; DTI_ADMIN is the technical administrator - read-only here, since this
        // module defines no separate audit or integration permission for a technical role to hold
        // instead (INTEGRATION_ENGINEER/SERVICE_INTEGRATION are granted the write grant directly below
        // because AVAMP's only write is the integration feed itself, not a distinct technical action).
        matrix.put(SflRole.SFL_ADMIN, READ_AND_MANAGE);
        matrix.put(SflRole.DTI_ADMIN, READ_ONLY);

        // The integration principals. AVAMP exists to be fed by the device and asset feeds, so these
        // two are what writes to it in anger; everything else is a human correcting a record.
        matrix.put(SflRole.INTEGRATION_ENGINEER, READ_AND_MANAGE);
        matrix.put(SflRole.SERVICE_INTEGRATION, READ_AND_MANAGE);

        // Estate management. A facilities director or manager reassigns custody of a tracked device
        // when a room changes hands, which is the ordinary human case for a write here.
        matrix.put(SflRole.FACILITIES_DIRECTOR, READ_AND_MANAGE);
        matrix.put(SflRole.FACILITIES_MANAGER, READ_AND_MANAGE);

        // The fleet office. The asset register is offered to them as part of the fleet programme (S168
        // asset tagging), and a register they can open but not write is a screen of buttons that all
        // answer 403. They own the vehicles and equipment they tag, move and hand over, so they hold the
        // write grant; the reporting viewer stays read-only below.
        matrix.put(SflRole.FLEET_MANAGER, READ_AND_MANAGE);
        matrix.put(SflRole.FLEET_LOGISTICS_OFFICER, READ_AND_MANAGE);
        // Operational read. Each of these is placing, escorting or accounting for physical things and
        // needs to know where a tracked device is; none of them needs to move it.
        matrix.put(SflRole.IFIMP_MAINTENANCE_SUPERVISOR, READ_ONLY);
        matrix.put(SflRole.IFIMP_TECHNICIAN, READ_ONLY);
        matrix.put(SflRole.CENTRE_MANAGER, READ_ONLY);
        matrix.put(SflRole.COMMAND_ROLE, READ_ONLY);
        matrix.put(SflRole.SECURITY_DIRECTOR, READ_ONLY);
        matrix.put(SflRole.SECURITY_OFFICER, READ_ONLY);
        matrix.put(SflRole.SOC_OPERATOR, READ_ONLY);
        matrix.put(SflRole.HSE_MANAGER, READ_ONLY);
        matrix.put(SflRole.DISPATCH_CONTROLLER, READ_ONLY);

        // Read and prove. Breadth is cheap because they change nothing.
        // Merged with the former AUDITOR role - identical here.
        matrix.put(SflRole.COMPLIANCE_OFFICER, READ_ONLY);

        return Map.copyOf(matrix);
    }
}
