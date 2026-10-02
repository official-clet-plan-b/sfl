import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FleetApiError } from 'shared/errors/FleetApiError';
import type { FacilityDashboard } from '../api/dto';

const getDashboard = vi.hoisted(() => vi.fn());
const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());

vi.mock('../api/facilitiesApi', () => ({ getDashboard }));
vi.mock('shared/layout/actorPermissions', () => ({ permits }));

const FacilitiesDashboardPage = (await import('./FacilitiesDashboardPage')).default;

const dashboard = (overrides: Partial<FacilityDashboard> = {}): FacilityDashboard =>
  ({
    siteCode: 'ACCRA',
    operatingMode: 'ROUTINE',
    generatedAt: '2026-07-30T09:00:00Z',
    spaces: {
      total: 4,
      ready: 3,
      degraded: 0,
      blocked: 1,
      unknown: 0,
      bookable: 3,
      availableForBooking: 2,
      examinationCapable: 2,
      availableForExamination: 1,
    },
    blockers: {
      critical: 1,
      major: 0,
      minor: 0,
      advisory: 0,
      total: 1,
      criticalBeyondEscalationWindow: 0,
    },
    assets: {
      total: 6,
      impaired: 1,
      criticalImpaired: 1,
      serviceOverdue: 2,
      serviceDueSoon: 1,
      warrantyExpiringSoon: 0,
    },
    maintenance: { openFaults: 3, openWorkOrders: 2 },
    readinessScore: 75,
    stale: false,
    staleWarning: null,
    examinationRisks: [],
    unavailableSpaces: [],
    staleReadiness: [],
    ...overrides,
  }) as FacilityDashboard;

const renderPage = () =>
  render(
    <MemoryRouter>
      <FacilitiesDashboardPage />
    </MemoryRouter>,
  );

describe('FacilitiesDashboardPage', () => {
  beforeEach(() => permits.mockReturnValue(true));

  it('shows the figures as loading while the dashboard is fetched', () => {
    getDashboard.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getAllByRole('status', { hidden: true }).length).toBeGreaterThan(0);
  });

  it('renders the readiness figures once loaded', async () => {
    getDashboard.mockResolvedValue(dashboard());
    renderPage();

    expect(await screen.findByText('75%')).toBeInTheDocument();
    expect(screen.getByText('3 of 4 spaces ready')).toBeInTheDocument();
    expect(screen.getByText('1 critical, of 6 assets')).toBeInTheDocument();
  });

  /**
   * The requirement this screen exists to satisfy.
   *
   * SRS-SFL-S152-05: "critical safety and examination-readiness indicators must display stale-data
   * warnings where freshness thresholds are breached". A dashboard showing confident numbers over
   * readiness nobody has checked converts absence of information into apparent good news.
   */
  it('shows the stale-data warning when readiness is out of date', async () => {
    getDashboard.mockResolvedValue(
      dashboard({
        stale: true,
        staleWarning: '2 space(s) have readiness older than PT24H and may not reflect the current state of the estate.',
      }),
    );
    renderPage();

    expect(await screen.findByText('Readiness data is stale')).toBeInTheDocument();
    expect(screen.getByText(/2 space\(s\) have readiness older than PT24H/)).toBeInTheDocument();
  });

  it('does not show the stale warning when the data is fresh', async () => {
    getDashboard.mockResolvedValue(dashboard());
    renderPage();

    await screen.findByText('75%');
    expect(screen.queryByText('Readiness data is stale')).not.toBeInTheDocument();
  });

  it('announces examination mode, because the rules change with it', async () => {
    getDashboard.mockResolvedValue(dashboard({ operatingMode: 'EXAMINATION' }));
    renderPage();

    expect(await screen.findByText('This centre is in examination mode')).toBeInTheDocument();
  });

  it('surfaces a failure with the service message rather than an empty screen', async () => {
    getDashboard.mockRejectedValue(
      new FleetApiError({
        status: 403,
        code: 'NO_SCOPE',
        message: 'No site scope is assigned to your user profile.',
      }),
    );
    renderPage();

    expect(
      await screen.findByText('No site scope is assigned to your user profile.'),
    ).toBeInTheDocument();
  });

  /**
   * The 403 that is not an error: a manager may see the totals and not the records behind them.
   * SRS-SFL-S152-05 calls this "Restricted Drilldown", and the screen explains it rather than
   * offering rows that would be refused.
   */
  it('explains the missing drilldown when the actor cannot open a record', async () => {
    permits.mockImplementation((permission) => permission !== 'FACILITIES_DASHBOARD_DRILLDOWN');
    getDashboard.mockResolvedValue(dashboard());
    renderPage();

    expect(
      await screen.findByText(/You can see these totals but not the records behind them/),
    ).toBeInTheDocument();
  });

  it('offers the drilldown explanation to nobody who has the permission', async () => {
    getDashboard.mockResolvedValue(dashboard());
    renderPage();

    await screen.findByText('75%');
    await waitFor(() =>
      expect(
        screen.queryByText(/You can see these totals but not the records behind them/),
      ).not.toBeInTheDocument(),
    );
  });

  it('shows an empty exception list as reassurance rather than as nothing', async () => {
    getDashboard.mockResolvedValue(dashboard());
    renderPage();

    expect(
      await screen.findByText('No examination-capable space is at risk.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Every bookable space is available.')).toBeInTheDocument();
  });
});
