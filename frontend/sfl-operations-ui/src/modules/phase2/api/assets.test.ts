import { describe, expect, it } from 'vitest';
import {
  changeSource,
  describeChange,
  draftFor,
  formatStoredLocation,
  joinWords,
  planAssetUpdate,
  validateRegister,
  type RegisterDraft,
} from './assets';
import type { AssetHistoryEntry, AssetReference } from './phase2Api';

const asset = (overrides: Partial<AssetReference> = {}): AssetReference => ({
  id: 'a-1',
  assetCode: 'AST-1',
  name: 'Projector',
  category: 'EQUIPMENT',
  status: 'ACTIVE',
  siteCode: 'CLET-HQ',
  locationType: 'ROOM',
  locationReference: 'ROOM 12',
  custodianReference: 'A. Mensah',
  externalReference: 'RFID-1',
  evidenceReference: null,
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
  ...overrides,
});

const entry = (overrides: Partial<AssetHistoryEntry> = {}): AssetHistoryEntry => ({
  id: 'h-1',
  assetId: 'a-1',
  changeType: 'MOVED',
  fromValue: 'SITE:CLET-HQ',
  toValue: 'ROOM:ROOM 12',
  source: 'MANUAL',
  sourceReference: null,
  actorId: 'operator@sfl.local',
  occurredAt: '2026-10-02T09:00:00Z',
  ...overrides,
});

describe('planAssetUpdate', () => {
  it('plans nothing when nothing changed', () => {
    expect(planAssetUpdate(asset(), draftFor(asset()))).toEqual([]);
  });

  it('ignores case and surrounding space, as the service does', () => {
    const draft = { ...draftFor(asset()), tag: ' rfid-1 ', locationReference: 'room 12', custodian: ' A. Mensah ' };
    expect(planAssetUpdate(asset(), draft)).toEqual([]);
  });

  it('sends only what differs', () => {
    expect(planAssetUpdate(asset(), { ...draftFor(asset()), custodian: 'B. Owusu' })).toEqual(['custody']);
    expect(planAssetUpdate(asset(), { ...draftFor(asset()), locationType: 'ZONE' })).toEqual(['location']);
    expect(planAssetUpdate(asset(), { ...draftFor(asset()), tag: 'RFID-2' })).toEqual(['tag']);
  });

  it('puts the tag first, because it is the call most likely to be refused', () => {
    const draft = { tag: 'RFID-2', locationType: 'ZONE' as const, locationReference: 'DOCK 2', custodian: 'B. Owusu' };
    expect(planAssetUpdate(asset(), draft)).toEqual(['tag', 'location', 'custody']);
  });

  it('treats an emptied custodian as handing the asset back', () => {
    expect(planAssetUpdate(asset(), { ...draftFor(asset()), custodian: '' })).toEqual(['custody']);
  });

  it('does not treat an untouched blank custodian as a change', () => {
    const unheld = asset({ custodianReference: null });
    expect(planAssetUpdate(unheld, draftFor(unheld))).toEqual([]);
  });

  it('never plans to clear a tag - a blank tag field means leave it alone', () => {
    expect(planAssetUpdate(asset(), { ...draftFor(asset()), tag: '' })).toEqual([]);
  });
});

describe('history wording', () => {
  it('reads a move as from → to with the place type spelled out', () => {
    expect(describeChange(entry())).toBe('Site · CLET-HQ → Room · ROOM 12');
  });

  it('reads a custody hand-over as from → to people', () => {
    expect(describeChange(entry({ changeType: 'CUSTODY_CHANGED', fromValue: 'A. Mensah', toValue: 'B. Owusu' }))).toBe(
      'A. Mensah → B. Owusu',
    );
  });

  it('reads a first custodian as "Set to"', () => {
    expect(describeChange(entry({ changeType: 'CUSTODY_CHANGED', fromValue: null, toValue: 'A. Mensah' }))).toBe(
      'Set to A. Mensah',
    );
  });

  it('reads registration as where it was registered', () => {
    expect(describeChange(entry({ changeType: 'REGISTERED', fromValue: null, toValue: 'SITE:CLET-HQ' }))).toBe(
      'Registered at Site · CLET-HQ',
    );
  });

  it('names the reader for a sighting and the person for a manual change', () => {
    expect(changeSource(entry({ source: 'READER', sourceReference: 'reader-7' }))).toBe('Reader reader-7');
    expect(changeSource(entry())).toBe('operator@sfl.local');
  });

  it('formats a stored location, tolerating a value with no type', () => {
    expect(formatStoredLocation('ROOM:ROOM 12')).toBe('Room · ROOM 12');
    expect(formatStoredLocation('somewhere')).toBe('somewhere');
    expect(formatStoredLocation(null)).toBe('None');
  });
});

describe('validateRegister', () => {
  const valid: RegisterDraft = {
    assetCode: 'AST-9',
    name: 'Laptop',
    category: 'EQUIPMENT',
    tag: '',
    locationType: 'SITE',
    locationReference: 'CLET-HQ',
    custodian: '',
  };

  it('accepts a complete asset with no tag yet', () => {
    expect(validateRegister(valid)).toEqual({});
  });

  it('asks for the required fields', () => {
    const errors = validateRegister({ ...valid, assetCode: ' ', name: '', category: '', locationReference: '' });
    expect(Object.keys(errors).sort()).toEqual(['assetCode', 'category', 'locationReference', 'name']);
  });

  it('refuses text the service would truncate or reject', () => {
    expect(validateRegister({ ...valid, tag: 'x'.repeat(161) }).tag).toBeDefined();
    expect(validateRegister({ ...valid, assetCode: 'x'.repeat(81) }).assetCode).toBeDefined();
    expect(validateRegister({ ...valid, custodian: 'x'.repeat(161) }).custodian).toBeDefined();
  });
});

describe('joinWords', () => {
  it('reads as a sentence', () => {
    expect(joinWords([])).toBe('');
    expect(joinWords(['tag'])).toBe('tag');
    expect(joinWords(['tag', 'location'])).toBe('tag and location');
    expect(joinWords(['tag', 'location', 'custody'])).toBe('tag, location and custody');
  });
});
