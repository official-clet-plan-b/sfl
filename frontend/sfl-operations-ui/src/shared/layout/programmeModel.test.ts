import { describe, expect, it } from 'vitest';
import { allProgrammes, allSystems, programmesFor, systems, systemsFor } from './programmeModel';

/**
 * Who sees what.
 *
 * The first tests in this dashboard, and this is the file that earned them: the module is
 * deliberately free of imports so the decision it encodes can be exercised directly, and the
 * comments in it already describe two live bugs that shipped - `COMMAND_ROLE` losing the whole FTLMP
 * side of the console, and `SECURITY_OFFICER` never seeing the dispatch exception it was meant to
 * escalate. Both were mapping mistakes no type checker would catch.
 */
describe('programme entitlement', () => {
  describe('S152 facilities', () => {
    it('entitles a facilities manager to every IFIMP system', () => {
      // S153 arrived with the CMMS module and S159 with booking. Entitlement to the three is
      // identical for this role, because the permission matrix puts fault, work-order, booking and
      // resource reads in its shared read-only set - see the note on `SystemCode` for why they are
      // still separate codes.
      expect(systemsFor(['FACILITIES_MANAGER'])).toEqual([
        'S152',
        'S153',
        'S156',
        'S157',
        'S158',
        'S159',
        'S169',
        'S173',
        'S176',
      ]);
      expect(programmesFor(['FACILITIES_MANAGER'])).toEqual(['IFIMP']);
    });

    it('gives every IFIMP role the facilities system', () => {
      const ifimpRoles = [
        'FACILITIES_DIRECTOR',
        'FACILITIES_MANAGER',
        'IFIMP_MAINTENANCE_SUPERVISOR',
        'IFIMP_TECHNICIAN',
        'IFIMP_REQUESTER',
        'VENDOR_TECHNICIAN',
      ];
      ifimpRoles.forEach((role) => {
        expect(systemsFor([role]), `${role} should see S152`).toContain('S152');
      });
    });

    it('withholds booking from a contractor and gives it to every other IFIMP role', () => {
      /*
        The one IFIMP role that is not entitled to S159, and the reason the split is worth a test:
        `VENDOR_TECHNICIAN` is the only facilities role whose matrix entry is an explicit `EnumSet`
        rather than a union with the shared `READ_ONLY` set. Adding `FACILITIES_BOOKING_READ` to that
        set therefore entitled ten roles to the room diary and left the contractor out - correctly,
        but silently. If somebody ever rebuilds `VENDOR_TECHNICIAN` on top of `READ_ONLY`, a
        contractor quietly gains the whole estate's diary, and this is what says so.
      */
      expect(systemsFor(['VENDOR_TECHNICIAN'])).not.toContain('S159');

      ['FACILITIES_DIRECTOR', 'IFIMP_MAINTENANCE_SUPERVISOR', 'IFIMP_REQUESTER'].forEach((role) => {
        expect(systemsFor([role]), `${role} should see S159`).toContain('S159');
      });
    });

    it('entitles a technician to booking for turnaround alone', () => {
      // S159 for a narrower reason than the rest: `IFIMP_TECHNICIAN` holds
      // `FACILITIES_SETUP_TASK_MANAGE` and no booking-request permission. The section renders with
      // the turnaround queue and nothing that reserves a hall - a technician who could book one
      // would be scheduling the estate from the shop floor.
      expect(systemsFor(['IFIMP_TECHNICIAN'])).toContain('S159');
    });

    it('does not show facilities to a fleet driver', () => {
      // The regression this whole model exists to prevent: a driver opening their portal onto the
      // estate register. They are FTLMP, and S152 is IFIMP.
      expect(systemsFor(['FLEET_DRIVER'])).not.toContain('S152');
      expect(programmesFor(['FLEET_DRIVER'])).not.toContain('IFIMP');
    });

    it('gives a centre manager both facilities and dispatch', () => {
      // A centre manager receives consignments *and* declares their centre's operating mode, so
      // they are genuinely in two programmes. Transcribed from the two permission matrices.
      const granted = systemsFor(['CENTRE_MANAGER']);
      expect(granted).toContain('S152');
      expect(granted).toContain('S171');
      expect(programmesFor(['CENTRE_MANAGER'])).toEqual(
        expect.arrayContaining(['IFIMP', 'FTLMP']),
      );
    });

    it('keeps command and integration roles across every system', () => {
      expect(systemsFor(['COMMAND_ROLE'])).toEqual(allSystems);
      expect(systemsFor(['INTEGRATION_ENGINEER'])).toEqual(allSystems);
    });

    it('maps the two implemented SSEMP workflows to the roles their backend matrices grant', () => {
      expect(systemsFor(['RECEPTION_OFFICER'])).toEqual(['S160']);
      expect(systemsFor(['VISITOR_HOST'])).toEqual(['S160']);
      expect(systemsFor(['INCIDENT_INVESTIGATOR'])).toEqual(['S163']);
      expect(systemsFor(['SOC_OPERATOR'])).toEqual(['S160', 'S163', 'S174']);
      expect(systemsFor(['SECURITY_DIRECTOR'])).toEqual(['S160', 'S163', 'S174']);
      expect(programmesFor(['INCIDENT_INVESTIGATOR'])).toEqual(['SSEMP']);
    });

    it('places S152 in IFIMP', () => {
      expect(systems.S152.programme).toBe('IFIMP');
      expect(systems.S152.label).toBe('Facility management');
    });

    it('names S159 as booking rather than as facilities', () => {
      // The no-entitlement page reads this label. A refused requester should be told they cannot see
      // "Room & resource booking" - which is what they came for - not "Facility management".
      expect(systems.S159.programme).toBe('IFIMP');
      expect(systems.S159.label).toBe('Room & resource booking');
    });
  });

  describe('the rules the model turns on', () => {
    it('gives a cross-programme role everything', () => {
      expect(systemsFor(['COMPLIANCE_OFFICER'])).toEqual(allSystems);
      // Three, not four: AVAMP was retired as a programme. It had no deployable of its own - its
      // entities live in the fleet service's `asset_visibility` schema - and no screens, so
      // entitlement to it granted nothing the shell could show.
      expect(programmesFor(['SFL_ADMIN'])).toEqual(allProgrammes);
      expect(allProgrammes).toHaveLength(3);
    });

    it('grants an unrecognised role nothing', () => {
      // Fail-closed for programmes: an actor with no known role sees nothing, because an empty
      // sidebar is a question somebody asks where a full one is not.
      expect(programmesFor(['NOT_A_REAL_ROLE'])).toEqual([]);
      expect(systemsFor(['NOT_A_REAL_ROLE'])).toEqual([]);
    });

    it('unions the systems of several roles', () => {
      const granted = systemsFor(['FACILITIES_MANAGER', 'DISPATCH_CONTROLLER']);
      expect(granted).toEqual(expect.arrayContaining(['S152', 'S171']));
      expect(granted).not.toContain('S174');
    });

    it("derives a role's programmes from its systems", () => {
      // The union that stops the two maps drifting: adding a system to `roleSystems` entitles the
      // role to that system's programme automatically.
      expect(programmesFor(['IFIMP_TECHNICIAN'])).toContain('IFIMP');
    });
  });
});
