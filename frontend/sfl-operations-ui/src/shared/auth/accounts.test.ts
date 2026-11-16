import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Which seeded accounts belong on which origin.
 *
 * <p>The sign-in page used to list all twenty on every service, which reads as an invitation and
 * was not one: signing into the facilities service as a driver produced a session with no facilities
 * capability and an empty dashboard, and nothing on the page had warned that would happen. On the
 * portal the full list is right, because the portal serves all three platforms.
 */

const serving = vi.hoisted(() => ({ value: 'ALL' as string }));
vi.mock('shared/platform', () => ({
  servingPlatform: () => serving.value,
  servesPlatform: (platform: string) => serving.value === 'ALL' || serving.value === platform,
  isPortal: () => serving.value === 'ALL',
  servingPlatformName: () => 'test service',
}));

const { seededAccounts, accountsForServingPlatform, accountIsForeignToPlatform, findAccount } =
  await import('./accounts');

beforeEach(() => {
  serving.value = 'ALL';
});

describe('accounts for the serving platform', () => {
  it('the portal lists every seeded account', () => {
    serving.value = 'ALL';
    expect(accountsForServingPlatform()).toHaveLength(seededAccounts.length);
  });

  it('an origin that never answered lists every account rather than none', () => {
    /*
      Deliberately the opposite of the permission fail-closed, and for a reason that does not
      contradict it. Permissions decide what somebody may do, so unknown must mean nothing. This
      decides which names to print on a sign-in form; printing none would leave a developer with a
      form they cannot use and no way to discover why, and it withholds nothing - sign-in itself is
      still refused by `signIn` if the account has no business here.
    */
    serving.value = 'UNKNOWN';
    expect(accountsForServingPlatform()).toHaveLength(seededAccounts.length);
  });

  it('the facilities service lists facilities accounts and not a fleet officer', () => {
    serving.value = 'IFIMP';
    const emails = accountsForServingPlatform().map((account) => account.email);

    expect(emails).toContain('facilitiesmanager@clet.gh');
    expect(emails).toContain('technician@clet.gh');
    expect(emails).not.toContain('fleetofficer@clet.gh');
  });

  it('the fleet service lists a fleet officer and not a facilities technician', () => {
    serving.value = 'FTLMP';
    const emails = accountsForServingPlatform().map((account) => account.email);

    expect(emails).toContain('fleetofficer@clet.gh');
    expect(emails).toContain('fleetmanager@clet.gh');
    expect(emails).not.toContain('technician@clet.gh');
  });

  it('a fleet officer is foreign to facilities and at home on fleet', () => {
    const driver = findAccount('fleetofficer@clet.gh');
    expect(driver).toBeDefined();

    serving.value = 'IFIMP';
    expect(accountIsForeignToPlatform(driver!)).toBe(true);

    serving.value = 'FTLMP';
    expect(accountIsForeignToPlatform(driver!)).toBe(false);
  });

  it('an account entitled to two platforms appears on both', () => {
    // A compliance officer works across programmes. Two services, two lists, the same person on
    // each - and a different set of screens once they are in, which is the scoping rule doing its
    // job.
    const complianceOfficer = findAccount('complianceofficer@clet.gh');
    expect(complianceOfficer).toBeDefined();

    serving.value = 'IFIMP';
    expect(accountIsForeignToPlatform(complianceOfficer!)).toBe(false);

    serving.value = 'FTLMP';
    expect(accountIsForeignToPlatform(complianceOfficer!)).toBe(false);
  });
});
