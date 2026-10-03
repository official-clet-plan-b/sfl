package gh.edu.clet.sfl.facilities.shared.domain.policy;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.common.security.SflRole;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Role to facilities-permission mapping for the SRS S152 and S153 user classes.
 *
 * <p>{@code SiteScopedPrincipal} carries roles and site scopes but no permissions, so permissions are
 * derived here rather than read from a token claim. Keeping the derivation in the facilities service
 * - not in {@code sfl-service-common} - means no IFIMP business rule leaks into the shared library,
 * and the matrix can be replaced by real token claims later without touching a single call site
 * (gap report C-07). Same decision as {@code FleetPermissionMatrix}, {@code FuelPermissionMatrix},
 * {@code DispatchPermissionMatrix} and {@code EmergencyPermissionMatrix}.
 *
 * <p>It lives in {@code shared} rather than in {@code masterdata} because S152 is the host platform:
 * {@code readiness}, {@code dashboard}, S153 {@code maintenance} and S159 {@code booking} all
 * authorise against this one matrix. A per-module matrix is how five modules end up disagreeing about
 * what a facilities manager may do.
 *
 * <p>The interesting grants, none of them incidental:
 * <ul>
 *   <li>{@link SflRole#IFIMP_TECHNICIAN} assesses readiness, changes an asset's operational status
 *       and works the jobs assigned to them - but cannot manage the estate, override a lock, change
 *       the operating mode, or <em>close</em> a work order. A technician marks work complete; a
 *       supervisor accepts it.</li>
 *   <li>{@link SflRole#VENDOR_TECHNICIAN} was the same set as the technician until S153 and is now
 *       much narrower: a contractor is not staff, and site scope is the wrong boundary for one. The
 *       real boundary is <strong>assignment</strong>, enforced per record in
 *       {@code WorkOrderApplicationService} because "the ones assigned to me" is a property of the
 *       record and not something a matrix can say.</li>
 *   <li>{@link SflRole#IFIMP_REQUESTER} reports a fault and follows their own, and reads nothing
 *       else - the fault read is narrowed per record the same way. A requester who could read the
 *       site's fault register would learn which halls are unusable and which security equipment is
 *       broken, which is not what reporting a leak earns.</li>
 *   <li>{@link SflPermission#FACILITIES_EVIDENCE_EXPORT} is held only by reviewers and
 *       administrators. SRS-SFL-S153-03 makes export a distinct authorised act with a recorded
 *       reason, not a stronger form of reading.</li>
 *   <li>{@link SflRole#COMMAND_ROLE} and {@link SflRole#CENTRE_MANAGER} hold
 *       {@link SflPermission#FACILITIES_OPERATING_MODE_CHANGE}. Declaring examination mode is a
 *       centre-level operational decision, and NFR 23.3 requires it to be role-restricted.</li>
 *   <li>{@link SflRole#COMPLIANCE_OFFICER} (merged with the former AUDITOR role, identical here)
 *       reads everything and changes nothing, and holds {@link SflPermission#FACILITIES_AUDIT_INTEGRITY_CHECK}
 *       alongside the administrators - an integrity failure is escalated to compliance, so compliance
 *       must be able to run the check.</li>
 * </ul>
 */
public final class FacilitiesPermissionMatrix {

    /** What any facilities-facing role can see. */
    private static final Set<SflPermission> READ_ONLY = EnumSet.of(
            SflPermission.FACILITIES_SITE_READ,
            SflPermission.FACILITIES_SPACE_READ,
            SflPermission.FACILITIES_ZONE_READ,
            SflPermission.FACILITIES_DEVICE_REFERENCE_READ,
            SflPermission.FACILITIES_ASSET_READ,
            SflPermission.FACILITIES_READINESS_READ,
            SflPermission.FACILITIES_DASHBOARD_READ,
            SflPermission.FACILITIES_FAULT_READ,
            SflPermission.FACILITIES_WORK_ORDER_READ,
            // S159. A room diary is the least sensitive thing in this service - knowing that Hall A is
            // taken on Tuesday is what stops two people planning for it - so every staff-facing role
            // that reads the estate reads its bookings. The narrow roles below, which do not take
            // READ_ONLY, are granted or refused it individually.
            SflPermission.FACILITIES_BOOKING_READ,
            SflPermission.FACILITIES_RESOURCE_READ);

    private static final Map<SflRole, Set<SflPermission>> MATRIX = buildMatrix();

    private FacilitiesPermissionMatrix() {
    }

    /** Permissions granted by a single role. */
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

        // Platform administration - everything S152 defines. SFL_ADMIN is the business/system
        // administrator and holds it all; DTI_ADMIN is the technical administrator and does not - see
        // its own grant below, alongside COMPLIANCE_OFFICER, which it matches.
        Set<SflPermission> administrator = EnumSet.allOf(SflPermission.class).stream()
                .filter(permission -> permission.name().startsWith("FACILITIES_"))
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(SflPermission.class)));
        matrix.put(SflRole.SFL_ADMIN, Set.copyOf(administrator));

        // Facilities director - the whole estate, including mode changes and overrides, but not
        // platform configuration, which is an administrative concern.
        matrix.put(SflRole.FACILITIES_DIRECTOR, union(READ_ONLY,
                SflPermission.FACILITIES_SITE_MANAGE,
                SflPermission.FACILITIES_SPACE_MANAGE,
                SflPermission.FACILITIES_ZONE_MANAGE,
                SflPermission.FACILITIES_ASSET_MANAGE,
                SflPermission.FACILITIES_DEVICE_REFERENCE_REGISTER,
                SflPermission.FACILITIES_READINESS_ASSESS,
                SflPermission.FACILITIES_READINESS_OVERRIDE,
                SflPermission.FACILITIES_READINESS_CHECKLIST_MANAGE,
                SflPermission.FACILITIES_OPERATING_MODE_CHANGE,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN,
                SflPermission.FACILITIES_AUDIT_READ,
                SflPermission.FACILITIES_CONFIG_READ,
                SflPermission.FACILITIES_FAULT_REPORT,
                SflPermission.FACILITIES_FAULT_TRIAGE,
                SflPermission.FACILITIES_WORK_ORDER_CREATE,
                SflPermission.FACILITIES_WORK_ORDER_ASSIGN,
                SflPermission.FACILITIES_WORK_ORDER_UPDATE,
                SflPermission.FACILITIES_WORK_ORDER_CLOSE,
                SflPermission.FACILITIES_WORK_ORDER_CANCEL,
                SflPermission.FACILITIES_PM_SCHEDULE_READ,
                SflPermission.FACILITIES_PM_SCHEDULE_MANAGE,
                SflPermission.FACILITIES_VENDOR_READ,
                SflPermission.FACILITIES_VENDOR_MANAGE,
                SflPermission.FACILITIES_EVIDENCE_READ,
                SflPermission.FACILITIES_EVIDENCE_ATTACH,
                SflPermission.FACILITIES_EVIDENCE_EXPORT,
                SflPermission.FACILITIES_BOOKING_REQUEST,
                SflPermission.FACILITIES_BOOKING_APPROVE,
                SflPermission.FACILITIES_BOOKING_CANCEL,
                SflPermission.FACILITIES_BOOKING_OVERRIDE,
                SflPermission.FACILITIES_RESOURCE_MANAGE,
                SflPermission.FACILITIES_SETUP_TASK_MANAGE));

        // Facilities manager - day-to-day estate management. No mode change: declaring an examination
        // is a centre-level decision, not an estate-maintenance one.
        matrix.put(SflRole.FACILITIES_MANAGER, union(READ_ONLY,
                SflPermission.FACILITIES_SITE_MANAGE,
                SflPermission.FACILITIES_SPACE_MANAGE,
                SflPermission.FACILITIES_ZONE_MANAGE,
                SflPermission.FACILITIES_ASSET_MANAGE,
                SflPermission.FACILITIES_DEVICE_REFERENCE_REGISTER,
                SflPermission.FACILITIES_READINESS_ASSESS,
                SflPermission.FACILITIES_READINESS_CHECKLIST_MANAGE,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN,
                SflPermission.FACILITIES_CONFIG_READ,
                SflPermission.FACILITIES_FAULT_REPORT,
                SflPermission.FACILITIES_FAULT_TRIAGE,
                SflPermission.FACILITIES_WORK_ORDER_CREATE,
                SflPermission.FACILITIES_WORK_ORDER_ASSIGN,
                SflPermission.FACILITIES_WORK_ORDER_UPDATE,
                SflPermission.FACILITIES_WORK_ORDER_CLOSE,
                SflPermission.FACILITIES_WORK_ORDER_CANCEL,
                SflPermission.FACILITIES_PM_SCHEDULE_READ,
                SflPermission.FACILITIES_PM_SCHEDULE_MANAGE,
                SflPermission.FACILITIES_VENDOR_READ,
                SflPermission.FACILITIES_VENDOR_MANAGE,
                SflPermission.FACILITIES_EVIDENCE_READ,
                SflPermission.FACILITIES_EVIDENCE_ATTACH,
                // No BOOKING_OVERRIDE, matching the absence of READINESS_OVERRIDE above. Booking into
                // a space readiness has refused is the same class of decision as declaring it ready
                // anyway, and this role holds neither.
                SflPermission.FACILITIES_BOOKING_REQUEST,
                SflPermission.FACILITIES_BOOKING_APPROVE,
                SflPermission.FACILITIES_BOOKING_CANCEL,
                SflPermission.FACILITIES_RESOURCE_MANAGE,
                SflPermission.FACILITIES_SETUP_TASK_MANAGE));

        // Maintenance supervisor - owns readiness and the asset register it depends on, and can
        // override a lock because a supervisor is who a blocked examination hall escalates to.
        matrix.put(SflRole.IFIMP_MAINTENANCE_SUPERVISOR, union(READ_ONLY,
                SflPermission.FACILITIES_SPACE_MANAGE,
                SflPermission.FACILITIES_ASSET_MANAGE,
                SflPermission.FACILITIES_DEVICE_REFERENCE_REGISTER,
                SflPermission.FACILITIES_READINESS_ASSESS,
                SflPermission.FACILITIES_READINESS_OVERRIDE,
                SflPermission.FACILITIES_READINESS_CHECKLIST_MANAGE,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN,
                SflPermission.FACILITIES_FAULT_REPORT,
                SflPermission.FACILITIES_FAULT_TRIAGE,
                SflPermission.FACILITIES_WORK_ORDER_CREATE,
                SflPermission.FACILITIES_WORK_ORDER_ASSIGN,
                SflPermission.FACILITIES_WORK_ORDER_UPDATE,
                SflPermission.FACILITIES_WORK_ORDER_CLOSE,
                SflPermission.FACILITIES_WORK_ORDER_CANCEL,
                SflPermission.FACILITIES_PM_SCHEDULE_READ,
                SflPermission.FACILITIES_PM_SCHEDULE_MANAGE,
                SflPermission.FACILITIES_VENDOR_READ,
                SflPermission.FACILITIES_EVIDENCE_READ,
                SflPermission.FACILITIES_EVIDENCE_ATTACH,
                // Books spaces for maintenance access - the RESERVED purpose - and runs the setups.
                //
                // Deliberately no BOOKING_OVERRIDE, even though this role holds READINESS_OVERRIDE.
                // The two would be redundant and the redundancy is harmful: a supervisor who needs a
                // blocked hall used should clear or downgrade the blocker, which leaves a readiness
                // record somebody can review, rather than book past it and leave the hall still
                // reading BLOCKED to everyone else.
                SflPermission.FACILITIES_BOOKING_REQUEST,
                SflPermission.FACILITIES_BOOKING_CANCEL,
                SflPermission.FACILITIES_RESOURCE_MANAGE,
                SflPermission.FACILITIES_SETUP_TASK_MANAGE));

        // In-house technician - field work. Assesses readiness, changes asset status, works the jobs
        // assigned to them.
        //
        // No CREATE and no ASSIGN: a technician who could raise and self-assign work would be outside
        // the queue the supervisor is accountable for. And no CLOSE, which is the more interesting
        // omission: a technician marks work COMPLETED and a supervisor accepts it. Giving them both
        // would collapse the two states the SRS separates ("Authorised user closes or verifies
        // closure") into one, and would let the person who did the job be the only person who ever
        // saw it - which is exactly what closure evidence exists to prevent.
        matrix.put(SflRole.IFIMP_TECHNICIAN, union(READ_ONLY,
                SflPermission.FACILITIES_READINESS_ASSESS,
                SflPermission.FACILITIES_ASSET_MANAGE,
                SflPermission.FACILITIES_FAULT_REPORT,
                SflPermission.FACILITIES_WORK_ORDER_UPDATE,
                SflPermission.FACILITIES_PM_SCHEDULE_READ,
                SflPermission.FACILITIES_EVIDENCE_READ,
                SflPermission.FACILITIES_EVIDENCE_ATTACH,
                // Turns rooms around before bookings. Not a booker: a technician who could reserve a
                // hall would be scheduling the estate from the shop floor.
                SflPermission.FACILITIES_SETUP_TASK_MANAGE));

        // Vendor technician - a contractor, and therefore NOT a technician with a different badge.
        //
        // This is the narrowest role in the matrix and the split from IFIMP_TECHNICIAN is deliberate:
        // the two shared a permission set before S153, which meant a contractor could read the whole
        // estate register, every fault at the site and every asset's condition. Site scope is the
        // wrong boundary for somebody who is not CLET staff.
        //
        // The permissions below are the outer bound; the real boundary is **assignment**, enforced
        // per record in WorkOrderApplicationService rather than here, because "the ones assigned to
        // me" is not a fact a role-to-permission table can express. A vendor sees the work orders
        // assigned to them and nothing else, and reads only the spaces and assets those touch.
        //
        // No FACILITIES_ASSET_MANAGE: a contractor reporting that a generator is now out of service
        // does it by completing the work order, which is reviewed, rather than by editing the asset
        // register directly and changing a hall's readiness on their own authority.
        matrix.put(SflRole.VENDOR_TECHNICIAN, EnumSet.of(
                SflPermission.FACILITIES_SITE_READ,
                SflPermission.FACILITIES_SPACE_READ,
                SflPermission.FACILITIES_ASSET_READ,
                SflPermission.FACILITIES_WORK_ORDER_READ,
                SflPermission.FACILITIES_WORK_ORDER_UPDATE,
                SflPermission.FACILITIES_EVIDENCE_ATTACH,
                SflPermission.FACILITIES_EVIDENCE_READ));

        // Requester - reports what they can see is wrong, and follows their own report. Nothing more.
        // FACILITIES_FAULT_READ is granted, and narrowed per record to the faults they reported: a
        // requester who could read the site's whole fault register would learn which halls are
        // unusable and which security equipment is broken, which is not what reporting a leak earns.
        //
        // S159 makes this the busiest role in the module rather than the narrowest: a requester is
        // exactly the person who books a room. BOOKING_READ is granted and narrowed per record to
        // their own bookings, the same treatment FACILITIES_FAULT_READ gets and for the same reason -
        // a full room diary would tell somebody which halls are empty and when.
        //
        // No BOOKING_CANCEL: cancelling one's own booking is allowed by the per-record rule in
        // BookingApplicationService, and the permission is what it takes to cancel somebody else's.
        matrix.put(SflRole.IFIMP_REQUESTER, EnumSet.of(
                SflPermission.FACILITIES_SITE_READ,
                SflPermission.FACILITIES_SPACE_READ,
                SflPermission.FACILITIES_FAULT_REPORT,
                SflPermission.FACILITIES_FAULT_READ,
                SflPermission.FACILITIES_BOOKING_READ,
                SflPermission.FACILITIES_BOOKING_REQUEST,
                SflPermission.FACILITIES_RESOURCE_READ));

        // Command - oversight across facilities and emergency; declares examination mode.
        matrix.put(SflRole.COMMAND_ROLE, union(READ_ONLY,
                SflPermission.FACILITIES_OPERATING_MODE_CHANGE,
                SflPermission.FACILITIES_READINESS_OVERRIDE,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN,
                SflPermission.FACILITIES_AUDIT_READ,
                SflPermission.FACILITIES_PM_SCHEDULE_READ,
                SflPermission.FACILITIES_EVIDENCE_READ,
                SflPermission.FACILITIES_BOOKING_APPROVE,
                SflPermission.FACILITIES_BOOKING_CANCEL,
                SflPermission.FACILITIES_BOOKING_OVERRIDE));

        // Centre manager - runs a centre, so declares its mode, reads its readiness, and owns its
        // diary. The role S159 expects to hold BOOKING_OVERRIDE in practice: deciding that an
        // examination will go ahead in a degraded hall is a centre-level operational call, made with
        // a recorded reason, and it is the same authority NFR 23.3 already gives this role over mode.
        matrix.put(SflRole.CENTRE_MANAGER, union(READ_ONLY,
                SflPermission.FACILITIES_OPERATING_MODE_CHANGE,
                SflPermission.FACILITIES_READINESS_ASSESS,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN,
                SflPermission.FACILITIES_BOOKING_REQUEST,
                SflPermission.FACILITIES_BOOKING_APPROVE,
                SflPermission.FACILITIES_BOOKING_CANCEL,
                SflPermission.FACILITIES_BOOKING_OVERRIDE,
                SflPermission.FACILITIES_SETUP_TASK_MANAGE));

        // Read-and-prove roles. Breadth is cheap because they change nothing.
        Set<SflPermission> assurance = union(READ_ONLY,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN,
                SflPermission.FACILITIES_AUDIT_READ,
                SflPermission.FACILITIES_AUDIT_INTEGRITY_CHECK,
                SflPermission.FACILITIES_CONFIG_READ,
                SflPermission.FACILITIES_PM_SCHEDULE_READ,
                SflPermission.FACILITIES_VENDOR_READ,
                SflPermission.FACILITIES_EVIDENCE_READ,
                // Export is the assurance function, not a stronger form of reading. SRS-SFL-S153-03
                // requires an approved reason with every export and audits the act itself, which is
                // why no operational role holds this and every holder of it is a reviewer.
                SflPermission.FACILITIES_EVIDENCE_EXPORT);
        // Merged with the former AUDITOR role - identical here.
        matrix.put(SflRole.COMPLIANCE_OFFICER, assurance);

        // DTI_ADMIN is the technical administrator: the same read-and-verify breadth as COMPLIANCE_OFFICER, plus
        // the one technical write this service defines (registering a device reference, the same
        // action INTEGRATION_ENGINEER/SERVICE_INTEGRATION hold below) - not estate management, mode
        // changes, readiness overrides, or any other business decision, which stay SFL_ADMIN's alone.
        matrix.put(SflRole.DTI_ADMIN, union(assurance, SflPermission.FACILITIES_DEVICE_REFERENCE_REGISTER));

        // Integration principals - maintain the feeds that carry device and asset data in, and need
        // to see whether what they sent landed. They do not operate the estate.
        Set<SflPermission> integration = union(READ_ONLY,
                SflPermission.FACILITIES_DEVICE_REFERENCE_REGISTER,
                SflPermission.FACILITIES_CONFIG_READ);
        matrix.put(SflRole.INTEGRATION_ENGINEER, union(integration, SflPermission.FACILITIES_CONFIG_MANAGE));
        matrix.put(SflRole.SERVICE_INTEGRATION, integration);

        // HSE manager - reads the estate to place an incident and judge a location's standing.
        matrix.put(SflRole.HSE_MANAGER, union(READ_ONLY, SflPermission.FACILITIES_DASHBOARD_DRILLDOWN));

        grantPhaseTwo(matrix);
        grantEstateRegisters(matrix);
        grantWaste(matrix);
        grantLostFound(matrix);
        grantCatering(matrix);
        grantLease(matrix);
        grantRegisterExport(matrix);

        return Map.copyOf(matrix);
    }

    /**
     * The Phase 2 IFIMP grants - S156, S157, S158, S169, S173, S176 (SRS 2026/002 §3.1).
     *
     * <p>Added as one block that unions into each role's existing set, rather than edited into the
     * Phase 1 entries above, so a reviewer can read every Phase 2 decision in one place and can see
     * that no Phase 1 grant moved. {@link #READ_ONLY} is deliberately <em>not</em> widened: it is shared
     * by roles such as {@link SflRole#HSE_MANAGER} whose Phase 1 reach was judged on its own, and
     * contractor insurance or a project budget is not what reading the estate earns.
     *
     * <p>The separations of duty worth reading twice, each named by the SRS:
     * <ul>
     *   <li><strong>S156</strong> - the {@link SflRole#FACILITIES_ENGINEER} writes threshold rules and
     *       cannot disable one. Disabling needs {@code BMS_RULE_OVERRIDE} and a named accountable owner
     *       (S156-02), held by the director and the maintenance supervisor - the author of a rule
     *       switching it off alone is exactly the silent disable the requirement forbids.</li>
     *   <li><strong>S157</strong> - the {@link SflRole#ENERGY_SUSTAINABILITY_OFFICER} enters manual meter
     *       reads and cannot verify a held one. "Held for supervisor verification" (S157-01) means
     *       somebody else; the per-record rule additionally refuses entered-by = verified-by.</li>
     *   <li><strong>S158</strong> - the {@link SflRole#SPACE_PLANNING_OFFICER} commits scenarios but does
     *       not approve an occupancy override: S158-02 requires "an accountable approver", which is the
     *       director.</li>
     *   <li><strong>S176</strong> - the {@link SflRole#CONSTRUCTION_PROJECT_MANAGER} registers projects and
     *       proposes variations and approves neither. Ordinary variations are approved by the facilities
     *       manager or director; the escalated approval past the cumulative threshold (S176-03) by the
     *       director alone. Handover into the operational register is a facilities act (S176-04's user
     *       story is the Facilities Officer), so it sits with the facilities manager, not the PM.</li>
     *   <li><strong>Ingest</strong> permissions are held only by the integration principals. A person
     *       who could post telemetry could fabricate a breach and raise a work order with it.</li>
     * </ul>
     */
    /**
     * The S170 hygiene grants (SRS-SFL-S170, "Common Roles" §2.3).
     *
     * <p>S170 is owned by Facilities and HSE: the facilities director and manager and the HSE manager read
     * and manage it. The compliance officer reads it, and reads evidence; the technical administrator
     * reads the register only. Nobody else holds any of the four permissions - in particular not the
     * requester or the contractor, because hygiene findings carry evidence the SRS (4.2) keeps from
     * requester views.
     */
    private static void grantEstateRegisters(Map<SflRole, Set<SflPermission>> matrix) {
        Set<SflPermission> read = EnumSet.of(SflPermission.FACILITIES_HYGIENE_READ);
        // Director and HSE manager own hygiene outcomes, so they alone verify and close. The facilities
        // manager runs the register day to day and can see evidence but not sign a finding off.
        grant(matrix, SflRole.FACILITIES_DIRECTOR, read, SflPermission.FACILITIES_HYGIENE_MANAGE,
                SflPermission.FACILITIES_HYGIENE_EVIDENCE_READ, SflPermission.FACILITIES_HYGIENE_VERIFY);
        grant(matrix, SflRole.FACILITIES_MANAGER, read, SflPermission.FACILITIES_HYGIENE_MANAGE,
                SflPermission.FACILITIES_HYGIENE_EVIDENCE_READ);
        grant(matrix, SflRole.HSE_MANAGER, read, SflPermission.FACILITIES_HYGIENE_MANAGE,
                SflPermission.FACILITIES_HYGIENE_EVIDENCE_READ, SflPermission.FACILITIES_HYGIENE_VERIFY);
        grant(matrix, SflRole.COMPLIANCE_OFFICER, read, SflPermission.FACILITIES_HYGIENE_EVIDENCE_READ);
        grant(matrix, SflRole.DTI_ADMIN, read);
    }

    /**
     * The S178 waste grants. Facilities runs the programme day to day and the sustainability officer
     * does the same; only the director and the HSE manager verify evidence and so can sign off a
     * hazardous chain of custody. The compliance officer and technical administrator read.
     */
    private static void grantWaste(Map<SflRole, Set<SflPermission>> matrix) {
        Set<SflPermission> read = EnumSet.of(SflPermission.FACILITIES_WASTE_READ);
        grant(matrix, SflRole.FACILITIES_DIRECTOR, read, SflPermission.FACILITIES_WASTE_MANAGE,
                SflPermission.FACILITIES_WASTE_VERIFY);
        grant(matrix, SflRole.FACILITIES_MANAGER, read, SflPermission.FACILITIES_WASTE_MANAGE);
        grant(matrix, SflRole.ENERGY_SUSTAINABILITY_OFFICER, read, SflPermission.FACILITIES_WASTE_MANAGE);
        grant(matrix, SflRole.HSE_MANAGER, read, SflPermission.FACILITIES_WASTE_VERIFY);
        grant(matrix, SflRole.COMPLIANCE_OFFICER, read);
        grant(matrix, SflRole.DTI_ADMIN, read);
    }

    /**
     * The S179 grants. Front desk, security and facilities run the register; only the facilities director and
     * the security director approve a release or a disposal. Private detail and claimant data go to the roles
     * that must match a claim to an item; the compliance officer and technical administrator read the masked
     * register only.
     */
    private static void grantLostFound(Map<SflRole, Set<SflPermission>> matrix) {
        Set<SflPermission> read = EnumSet.of(SflPermission.FACILITIES_LOSTFOUND_READ);
        grant(matrix, SflRole.FACILITIES_DIRECTOR, read, SflPermission.FACILITIES_LOSTFOUND_MANAGE,
                SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ, SflPermission.FACILITIES_LOSTFOUND_APPROVE);
        grant(matrix, SflRole.FACILITIES_MANAGER, read, SflPermission.FACILITIES_LOSTFOUND_MANAGE,
                SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ);
        grant(matrix, SflRole.SECURITY_DIRECTOR, read, SflPermission.FACILITIES_LOSTFOUND_MANAGE,
                SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ, SflPermission.FACILITIES_LOSTFOUND_APPROVE);
        grant(matrix, SflRole.SECURITY_OFFICER, read, SflPermission.FACILITIES_LOSTFOUND_MANAGE,
                SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ);
        grant(matrix, SflRole.RECEPTION_OFFICER, read, SflPermission.FACILITIES_LOSTFOUND_MANAGE,
                SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ);
        grant(matrix, SflRole.CENTRE_MANAGER, read, SflPermission.FACILITIES_LOSTFOUND_MANAGE,
                SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ);
        grant(matrix, SflRole.COMPLIANCE_OFFICER, read);
        grant(matrix, SflRole.DTI_ADMIN, read);
    }

    /**
     * The S172 grants. The facilities and event-logistics coordinators plan and run services and may see
     * dietary needs; the director and the HSE manager approve - the HSE manager because a food-safety
     * exception is theirs to accept. The compliance officer and technical administrator read the register
     * without dietary detail.
     */
    private static void grantCatering(Map<SflRole, Set<SflPermission>> matrix) {
        Set<SflPermission> read = EnumSet.of(SflPermission.FACILITIES_CATERING_READ);
        grant(matrix, SflRole.FACILITIES_DIRECTOR, read, SflPermission.FACILITIES_CATERING_MANAGE,
                SflPermission.FACILITIES_CATERING_APPROVE, SflPermission.FACILITIES_CATERING_DIETARY_READ);
        grant(matrix, SflRole.FACILITIES_MANAGER, read, SflPermission.FACILITIES_CATERING_MANAGE,
                SflPermission.FACILITIES_CATERING_DIETARY_READ);
        grant(matrix, SflRole.EVENT_LOGISTICS_COORDINATOR, read, SflPermission.FACILITIES_CATERING_MANAGE,
                SflPermission.FACILITIES_CATERING_DIETARY_READ);
        grant(matrix, SflRole.HSE_MANAGER, read, SflPermission.FACILITIES_CATERING_APPROVE,
                SflPermission.FACILITIES_CATERING_DIETARY_READ);
        grant(matrix, SflRole.COMPLIANCE_OFFICER, read);
        grant(matrix, SflRole.DTI_ADMIN, read);
    }

    /**
     * The S177 grants. Facilities runs the portfolio; only the director approves an agreement or a material
     * amendment. The compliance officer reads, can clear a conflicting amendment out of legal review and sees
     * the financial detail, because those are an auditor's questions. Space planning and construction read the
     * register; they are told when a lease affects their space but do not see the money.
     */
    private static void grantLease(Map<SflRole, Set<SflPermission>> matrix) {
        Set<SflPermission> read = EnumSet.of(SflPermission.FACILITIES_LEASE_READ);
        grant(matrix, SflRole.FACILITIES_DIRECTOR, read, SflPermission.FACILITIES_LEASE_MANAGE,
                SflPermission.FACILITIES_LEASE_APPROVE, SflPermission.FACILITIES_LEASE_LEGAL_REVIEW,
                SflPermission.FACILITIES_LEASE_FINANCIAL_READ);
        grant(matrix, SflRole.FACILITIES_MANAGER, read, SflPermission.FACILITIES_LEASE_MANAGE,
                SflPermission.FACILITIES_LEASE_FINANCIAL_READ);
        grant(matrix, SflRole.SPACE_PLANNING_OFFICER, read, SflPermission.FACILITIES_LEASE_MANAGE);
        grant(matrix, SflRole.CONSTRUCTION_PROJECT_MANAGER, read);
        grant(matrix, SflRole.COMPLIANCE_OFFICER, read, SflPermission.FACILITIES_LEASE_LEGAL_REVIEW,
                SflPermission.FACILITIES_LEASE_FINANCIAL_READ);
        grant(matrix, SflRole.DTI_ADMIN, read);
    }

    /**
     * Exporting a register is the assurance function (NFR-AUD1, SRS 4.2), not a stronger form of reading: held by the
     * director, the compliance officer and the HSE manager. What an export contains is still what the caller may read -
     * private, dietary and financial columns follow their own grants.
     */
    private static void grantRegisterExport(Map<SflRole, Set<SflPermission>> matrix) {
        Set<SflPermission> export = EnumSet.of(SflPermission.FACILITIES_REGISTER_EXPORT);
        grant(matrix, SflRole.FACILITIES_DIRECTOR, export);
        grant(matrix, SflRole.COMPLIANCE_OFFICER, export);
        grant(matrix, SflRole.HSE_MANAGER, export);
        // Retention periods are statutory decisions, so they belong to the director and the compliance officer, not to platform configuration.
        grant(matrix, SflRole.FACILITIES_DIRECTOR, EnumSet.of(SflPermission.FACILITIES_RETENTION_MANAGE));
        grant(matrix, SflRole.COMPLIANCE_OFFICER, EnumSet.of(SflPermission.FACILITIES_RETENTION_MANAGE));
    }

    private static void grantPhaseTwo(Map<SflRole, Set<SflPermission>> matrix) {
        Set<SflPermission> phaseTwoRead = EnumSet.of(
                SflPermission.FACILITIES_BMS_READ,
                SflPermission.FACILITIES_ENERGY_READ,
                SflPermission.FACILITIES_SPACE_PLAN_READ,
                SflPermission.FACILITIES_CLEANING_READ,
                SflPermission.FACILITIES_EVENT_READ,
                SflPermission.FACILITIES_PROJECT_READ,
                SflPermission.FACILITIES_VENDOR_INTEGRATION_READ);

        grant(matrix, SflRole.FACILITIES_DIRECTOR, phaseTwoRead,
                SflPermission.FACILITIES_BMS_RULE_OVERRIDE,
                SflPermission.FACILITIES_BMS_QUARANTINE_RESOLVE,
                SflPermission.FACILITIES_ENERGY_READING_VERIFY,
                SflPermission.FACILITIES_ENERGY_BUDGET_MANAGE,
                SflPermission.FACILITIES_SPACE_PLAN_COMMIT,
                SflPermission.FACILITIES_OCCUPANCY_OVERRIDE_APPROVE,
                SflPermission.FACILITIES_SPACE_CHANGE_REQUEST,
                SflPermission.FACILITIES_SPACE_CHANGE_DECIDE,
                SflPermission.FACILITIES_CLEANING_TASK_SUPERVISE,
                SflPermission.FACILITIES_CLEANING_VENDOR_MANAGE,
                SflPermission.FACILITIES_EVENT_COORDINATE,
                SflPermission.FACILITIES_PROJECT_APPROVE,
                SflPermission.FACILITIES_PROJECT_HANDOVER,
                SflPermission.FACILITIES_PROJECT_CLOSE,
                SflPermission.FACILITIES_VARIATION_APPROVE,
                SflPermission.FACILITIES_VARIATION_ESCALATED_APPROVE);

        // Facilities manager - also the Cleaning Supervisor, Facilities Officer and, where no dedicated
        // coordinator exists, the event coordinator of the S169/S173/S176 user stories.
        grant(matrix, SflRole.FACILITIES_MANAGER, phaseTwoRead,
                SflPermission.FACILITIES_BMS_QUARANTINE_RESOLVE,
                SflPermission.FACILITIES_ENERGY_READING_VERIFY,
                SflPermission.FACILITIES_SPACE_CHANGE_REQUEST,
                SflPermission.FACILITIES_SPACE_CHANGE_DECIDE,
                SflPermission.FACILITIES_CLEANING_SCHEDULE_MANAGE,
                SflPermission.FACILITIES_CLEANING_REQUEST,
                SflPermission.FACILITIES_CLEANING_TASK_SUPERVISE,
                SflPermission.FACILITIES_CLEANING_VENDOR_MANAGE,
                SflPermission.FACILITIES_EVENT_COORDINATE,
                SflPermission.FACILITIES_PROJECT_HANDOVER,
                SflPermission.FACILITIES_VARIATION_APPROVE);

        grant(matrix, SflRole.IFIMP_MAINTENANCE_SUPERVISOR, phaseTwoRead,
                SflPermission.FACILITIES_BMS_RULE_OVERRIDE,
                SflPermission.FACILITIES_BMS_DEVICE_MANAGE,
                SflPermission.FACILITIES_BMS_QUARANTINE_RESOLVE,
                SflPermission.FACILITIES_CLEANING_TASK_SUPERVISE);

        grant(matrix, SflRole.IFIMP_TECHNICIAN, EnumSet.of(SflPermission.FACILITIES_BMS_READ,
                SflPermission.FACILITIES_CLEANING_READ),
                SflPermission.FACILITIES_CLEANING_TASK_EXECUTE);

        // A contractor cleaner works the tasks assigned to them and nothing else; the per-record rule in
        // S169 narrows CLEANING_READ to those, as S153 narrows WORK_ORDER_READ.
        grant(matrix, SflRole.VENDOR_TECHNICIAN, EnumSet.of(SflPermission.FACILITIES_CLEANING_READ),
                SflPermission.FACILITIES_CLEANING_TASK_EXECUTE);

        // Occupant and unit head: a reactive cleaning request, feedback on a finished clean, and a
        // space-change request - each narrowed per record to the requester's own.
        grant(matrix, SflRole.IFIMP_REQUESTER, EnumSet.noneOf(SflPermission.class),
                SflPermission.FACILITIES_CLEANING_REQUEST,
                SflPermission.FACILITIES_CLEANING_FEEDBACK_SUBMIT,
                SflPermission.FACILITIES_SPACE_CHANGE_REQUEST);

        grant(matrix, SflRole.FACILITIES_ENGINEER, union(READ_ONLY, phaseTwoRead.toArray(SflPermission[]::new)),
                SflPermission.FACILITIES_BMS_RULE_MANAGE,
                SflPermission.FACILITIES_BMS_DEVICE_MANAGE,
                SflPermission.FACILITIES_BMS_QUARANTINE_RESOLVE,
                SflPermission.FACILITIES_DEVICE_REFERENCE_REGISTER,
                SflPermission.FACILITIES_FAULT_REPORT,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN);

        grant(matrix, SflRole.ENERGY_SUSTAINABILITY_OFFICER,
                union(READ_ONLY, SflPermission.FACILITIES_BMS_READ, SflPermission.FACILITIES_ENERGY_READ,
                        SflPermission.FACILITIES_VENDOR_INTEGRATION_READ),
                SflPermission.FACILITIES_ENERGY_METER_MANAGE,
                SflPermission.FACILITIES_ENERGY_READING_ENTER,
                SflPermission.FACILITIES_ENERGY_BUDGET_MANAGE,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN);

        grant(matrix, SflRole.SPACE_PLANNING_OFFICER,
                union(READ_ONLY, SflPermission.FACILITIES_SPACE_PLAN_READ, SflPermission.FACILITIES_PROJECT_READ),
                SflPermission.FACILITIES_SPACE_PLAN_MANAGE,
                SflPermission.FACILITIES_SPACE_PLAN_COMMIT,
                SflPermission.FACILITIES_OCCUPANCY_STANDARD_MANAGE,
                SflPermission.FACILITIES_SPACE_CHANGE_REQUEST,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN);

        grant(matrix, SflRole.CONSTRUCTION_PROJECT_MANAGER,
                union(READ_ONLY, SflPermission.FACILITIES_PROJECT_READ, SflPermission.FACILITIES_SPACE_PLAN_READ),
                SflPermission.FACILITIES_PROJECT_MANAGE,
                SflPermission.FACILITIES_CONTRACTOR_MANAGE,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN);

        grant(matrix, SflRole.EVENT_LOGISTICS_COORDINATOR,
                union(READ_ONLY, SflPermission.FACILITIES_EVENT_READ, SflPermission.FACILITIES_CLEANING_READ),
                SflPermission.FACILITIES_EVENT_COORDINATE,
                SflPermission.FACILITIES_BOOKING_REQUEST,
                SflPermission.FACILITIES_CLEANING_REQUEST,
                SflPermission.FACILITIES_DASHBOARD_DRILLDOWN);

        // HSE: configures which event categories are higher-risk (S173-03) and verifies contractor
        // compliance before site access (S176-02's user story is the HSS unit itself).
        grant(matrix, SflRole.HSE_MANAGER, EnumSet.of(SflPermission.FACILITIES_EVENT_READ,
                SflPermission.FACILITIES_PROJECT_READ, SflPermission.FACILITIES_BMS_READ),
                SflPermission.FACILITIES_EVENT_RISK_CATEGORY_MANAGE,
                SflPermission.FACILITIES_CONTRACTOR_MANAGE);

        grant(matrix, SflRole.COMMAND_ROLE, EnumSet.of(SflPermission.FACILITIES_BMS_READ,
                SflPermission.FACILITIES_EVENT_READ));
        grant(matrix, SflRole.CENTRE_MANAGER, EnumSet.of(SflPermission.FACILITIES_BMS_READ,
                SflPermission.FACILITIES_EVENT_READ, SflPermission.FACILITIES_CLEANING_READ),
                SflPermission.FACILITIES_CLEANING_REQUEST);

        // Read-and-prove roles read every Phase 2 register and change none.
        grant(matrix, SflRole.COMPLIANCE_OFFICER, phaseTwoRead);
        grant(matrix, SflRole.DTI_ADMIN, phaseTwoRead);

        // Integration principals: the only holders of the ingest permissions.
        Set<SflPermission> ingest = EnumSet.of(
                SflPermission.FACILITIES_BMS_READ,
                SflPermission.FACILITIES_VENDOR_INTEGRATION_READ,
                SflPermission.FACILITIES_BMS_TELEMETRY_INGEST,
                SflPermission.FACILITIES_ENERGY_READING_INGEST,
                SflPermission.FACILITIES_EVENT_HANDOFF_INGEST);
        grant(matrix, SflRole.INTEGRATION_ENGINEER, ingest);
        grant(matrix, SflRole.SERVICE_INTEGRATION, ingest);
    }

    private static void grant(Map<SflRole, Set<SflPermission>> matrix, SflRole role, Set<SflPermission> base,
            SflPermission... extra) {
        EnumSet<SflPermission> combined = EnumSet.noneOf(SflPermission.class);
        combined.addAll(matrix.getOrDefault(role, Set.of()));
        combined.addAll(base);
        combined.addAll(Set.of(extra));
        matrix.put(role, Set.copyOf(combined));
    }

    private static Set<SflPermission> union(Set<SflPermission> base, SflPermission... extra) {
        EnumSet<SflPermission> combined = EnumSet.copyOf(base);
        combined.addAll(Set.of(extra));
        return Set.copyOf(combined);
    }
}
