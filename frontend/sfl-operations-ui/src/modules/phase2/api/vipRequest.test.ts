import { describe, expect, it } from 'vitest';
import {
  PRINCIPAL_MAX,
  VIP_PURPOSE_PREFIX,
  parseVipPurpose,
  toTripRequest,
  validateVipRequest,
  vipPurpose,
  type VipRequestDraft,
} from './vipRequest';

const NOW = new Date('2026-10-02T12:00:00');

const draft = (overrides: Partial<VipRequestDraft> = {}): VipRequestDraft => ({
  principal: 'Registrar',
  destination: 'Kotoka International Airport',
  start: '2026-10-05T10:00',
  end: '2026-10-05T12:00',
  chaseCar: false,
  ...overrides,
});

describe('the VIP purpose marker', () => {
  it('round-trips a principal, with and without chase-car support', () => {
    expect(parseVipPurpose(vipPurpose('Board chair', false))).toEqual({ principal: 'Board chair', chaseCar: false });
    expect(parseVipPurpose(vipPurpose('Board chair', true))).toEqual({ principal: 'Board chair', chaseCar: true });
  });

  it('starts with the prefix the service is asked to filter on', () => {
    expect(vipPurpose('Registrar', false).startsWith(VIP_PURPOSE_PREFIX)).toBe(true);
  });

  it('trims the principal', () => {
    expect(vipPurpose('  Registrar  ', false)).toBe(`${VIP_PURPOSE_PREFIX}Registrar`);
  });

  it('does not mistake an ordinary trip for a VIP one', () => {
    expect(parseVipPurpose('Courier run to the post office')).toBeNull();
    expect(parseVipPurpose('vip transport: lower case is not ours')).toBeNull();
  });
});

describe('validateVipRequest', () => {
  it('accepts a complete request', () => {
    expect(validateVipRequest(draft(), NOW)).toEqual({});
  });

  it('asks for everything that is missing', () => {
    const errors = validateVipRequest(draft({ principal: ' ', destination: '', start: '', end: '' }), NOW);
    expect(Object.keys(errors).sort()).toEqual(['destination', 'end', 'principal', 'start']);
  });

  it('refuses a start in the past', () => {
    expect(validateVipRequest(draft({ start: '2026-10-01T10:00', end: '2026-10-05T12:00' }), NOW).start).toMatch(
      /future/,
    );
  });

  it('refuses an end before, or equal to, the start', () => {
    expect(validateVipRequest(draft({ end: '2026-10-05T09:00' }), NOW).end).toMatch(/after/);
    expect(validateVipRequest(draft({ end: '2026-10-05T10:00' }), NOW).end).toMatch(/after/);
  });

  it('refuses a principal that would overflow the 500-character purpose', () => {
    expect(validateVipRequest(draft({ principal: 'x'.repeat(PRINCIPAL_MAX + 1) }), NOW).principal).toMatch(/under/);
  });

  it('does not also complain about the end when the start is what is wrong', () => {
    expect(validateVipRequest(draft({ start: '' }), NOW).end).toBeUndefined();
  });
});

describe('toTripRequest', () => {
  it('names no vehicle and no driver - assignment is the transport office’s call', () => {
    const request = toTripRequest(draft(), 'CLET-HQ');
    expect(request.vehicleId).toBeNull();
    expect(request.driverId).toBeNull();
  });

  it('carries the site, destination and ISO times', () => {
    const request = toTripRequest(draft({ destination: '  Airport  ' }), 'CLET-HQ');
    expect(request.siteCode).toBe('CLET-HQ');
    expect(request.destination).toBe('Airport');
    expect(request.plannedStart).toBe(new Date('2026-10-05T10:00').toISOString());
    expect(request.plannedEnd).toBe(new Date('2026-10-05T12:00').toISOString());
  });

  it('marks chase-car support in the purpose', () => {
    expect(toTripRequest(draft({ chaseCar: true }), 'CLET-HQ').purpose).toContain('chase-car requested');
  });
});
