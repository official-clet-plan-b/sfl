import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * What each S165 role is offered in the sidebar, pinned against `RiskAssessmentPermissionMatrix`.
 *
 * Mocked the way `navigationPermissions.test.ts` mocks: `permits` is backed by a live
 * `/actor/permissions` call that cannot run here, so each role's grant is transcribed from the Java
 * matrix below, and each assertion says why. A matrix change that is not reflected here fails loudly.
 */

const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
vi.mock('shared/layout/actorPermissions', () => ({
  permits,
  permitsAny: (permissions: string | string[]) =>
    (Array.isArray(permissions) ? permissions : [permissions]).some((p) => permits(p)),
  actorIsReviewer: () => false,
}));
vi.mock('shared/platform', () => ({
  servesPlatform: () => true,
  isPortal: () => true,
  servingPlatform: () => 'ALL',
  servingPlatformName: () => 'SFL Operations',
}));
vi.mock('shared/layout/programmes', async () => {
  const model = await vi.importActual<typeof import('shared/layout/programmeModel')>('shared/layout/programmeModel');
  return { ...model, entitledTo: () => true, entitledToSystem: (code: string) => code === 'S165', portalLabel: () => 'test' };
});
vi.mock('shared/layout/personas', () => ({ isPersona: () => false, PersonaCode: {} }));

const { entitledSections } = await import('shared/layout/navigation');

const ALL = [
  'RISK_ASSESSMENT_READ',
  'RISK_ASSESSMENT_AUTHOR',
  'RISK_ASSESSMENT_PUBLISH',
  'RISK_ASSESSMENT_SIGN_OFF',
  'RISK_ASSESSMENT_REVIEW_FLAG_MANAGE',
  'RISK_ASSESSMENT_ANALYTICS_READ',
  'RISK_ASSESSMENT_CONFIGURE',
];
/** HSE_MANAGER holds the module. */
const HSE_MANAGER = ALL;
/** SECURITY_DIRECTOR, COMPLIANCE_OFFICER, COMMAND_ROLE: read and analytics. */
const DIRECTOR = ['RISK_ASSESSMENT_READ', 'RISK_ASSESSMENT_ANALYTICS_READ'];
/** CONSTRUCTION_PROJECT_MANAGER, EVENT_LOGISTICS_COORDINATOR, INCIDENT_INVESTIGATOR: read only. */
const READER = ['RISK_ASSESSMENT_READ'];

const offered = (granted: string[]) => {
  permits.mockImplementation((p) => p === undefined || granted.includes(p));
  return entitledSections()
    .filter((section) => section.system === 'S165')
    .flatMap((section) => section.items.map((item) => item.label));
};

describe('the S165 sidebar', () => {
  beforeEach(() => permits.mockReset());

  it('offers the HSE manager every screen', () => {
    expect(offered(HSE_MANAGER)).toEqual([
      'Risk dashboard',
      'Assessment register',
      'Review flags',
      'Coverage and hazards',
      'Templates and review cycle',
    ]);
  });

  it('offers a director the standing and the coverage, and no queue or configuration to work', () => {
    expect(offered(DIRECTOR)).toEqual(['Risk dashboard', 'Assessment register', 'Coverage and hazards']);
  });

  it('offers a consumer role only the register, which is where it finds an assessment to link', () => {
    expect(offered(READER)).toEqual(['Assessment register']);
  });

  it('offers nothing to a role holding no S165 permission', () => {
    expect(offered(['INCIDENT_REPORT_READ'])).toEqual([]);
  });
});
