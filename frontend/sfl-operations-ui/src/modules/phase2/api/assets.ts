import { humanise } from 'modules/fleet/api/enums';
import type {
  AssetChangeType,
  AssetHistoryEntry,
  AssetReference,
  AssetCategory,
  LocationType,
} from './phase2Api';

export const CODE_MAX = 80;
export const NAME_MAX = 160;
export const TAG_MAX = 160;
export const LOCATION_MAX = 120;
export const CUSTODIAN_MAX = 160;

/** "ROOM:ROOM 12" as the service stores a change, shown as "Room · ROOM 12". */
export const formatStoredLocation = (stored: string | null): string => {
  if (!stored) {
    return 'None';
  }
  const split = stored.indexOf(':');
  return split < 0 ? stored : `${humanise(stored.slice(0, split))} · ${stored.slice(split + 1)}`;
};

export const formatLocation = (type: LocationType, reference: string): string =>
  `${humanise(type)} · ${reference}`;

const CHANGE_LABELS: Record<AssetChangeType, string> = {
  REGISTERED: 'Registered',
  TAG_ASSIGNED: 'Tag assigned',
  MOVED: 'Moved',
  CUSTODY_CHANGED: 'Custody changed',
  EVIDENCE_LINKED: 'Evidence linked',
};

export const changeLabel = (type: AssetChangeType): string => CHANGE_LABELS[type] ?? humanise(type);

/** What a history row says happened, in words a custodian can read back. */
export const describeChange = (entry: AssetHistoryEntry): string => {
  const place = entry.changeType === 'MOVED' || entry.changeType === 'REGISTERED';
  const from = place ? formatStoredLocation(entry.fromValue) : (entry.fromValue ?? 'Nobody');
  const to = place ? formatStoredLocation(entry.toValue) : (entry.toValue ?? 'Nobody');
  if (entry.changeType === 'REGISTERED') {
    return `Registered at ${to}`;
  }
  if (entry.fromValue === null) {
    return `Set to ${to}`;
  }
  return `${from} → ${to}`;
};

/** Who or what made the change: a person, or the reader that saw the tag. */
export const changeSource = (entry: AssetHistoryEntry): string =>
  entry.source === 'READER' ? `Reader ${entry.sourceReference ?? 'unknown'}` : entry.actorId;

export interface AssetDraft {
  locationType: LocationType;
  locationReference: string;
  custodian: string;
  tag: string;
}

export const draftFor = (asset: AssetReference): AssetDraft => ({
  locationType: asset.locationType,
  locationReference: asset.locationReference,
  custodian: asset.custodianReference ?? '',
  tag: asset.externalReference ?? '',
});

export type UpdateStep = 'tag' | 'location' | 'custody';

/**
 * The calls an update actually needs.
 *
 * Only what differs from the asset is sent. Sending all three every time wrote a history entry for a
 * custodian "changing" to the person who already had it, and made one failed call look like three.
 * The tag goes first because it is the one most likely to be refused (it may belong to another
 * asset), and a refusal should stop the update before anything has been changed.
 */
export const planAssetUpdate = (asset: AssetReference, draft: AssetDraft): UpdateStep[] => {
  const steps: UpdateStep[] = [];
  if (draft.tag.trim() && draft.tag.trim().toLowerCase() !== (asset.externalReference ?? '').toLowerCase()) {
    steps.push('tag');
  }
  if (
    draft.locationType !== asset.locationType ||
    draft.locationReference.trim().toLowerCase() !== asset.locationReference.toLowerCase()
  ) {
    steps.push('location');
  }
  if (draft.custodian.trim() !== (asset.custodianReference ?? '')) {
    steps.push('custody');
  }
  return steps;
};

export const STEP_LABELS: Record<UpdateStep, string> = {
  tag: 'tag',
  location: 'location',
  custody: 'custody',
};

/** "tag", "tag and location", "tag, location and custody". */
export const joinWords = (words: string[]): string =>
  words.length <= 1 ? (words[0] ?? '') : `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`;

export interface RegisterDraft {
  assetCode: string;
  name: string;
  category: AssetCategory | '';
  tag: string;
  locationType: LocationType;
  locationReference: string;
  custodian: string;
}

export type RegisterErrors = Partial<Record<'assetCode' | 'name' | 'category' | 'tag' | 'locationReference' | 'custodian', string>>;

export const validateRegister = (draft: RegisterDraft): RegisterErrors => {
  const errors: RegisterErrors = {};
  if (!draft.assetCode.trim()) {
    errors.assetCode = 'Give the asset a code.';
  } else if (draft.assetCode.trim().length > CODE_MAX) {
    errors.assetCode = `Keep this under ${CODE_MAX} characters.`;
  }
  if (!draft.name.trim()) {
    errors.name = 'Say what is being tracked.';
  } else if (draft.name.trim().length > NAME_MAX) {
    errors.name = `Keep this under ${NAME_MAX} characters.`;
  }
  if (!draft.category) {
    errors.category = 'Choose a category.';
  }
  if (draft.tag.trim().length > TAG_MAX) {
    errors.tag = `Keep this under ${TAG_MAX} characters.`;
  }
  if (!draft.locationReference.trim()) {
    errors.locationReference = 'Say where it is.';
  } else if (draft.locationReference.trim().length > LOCATION_MAX) {
    errors.locationReference = `Keep this under ${LOCATION_MAX} characters.`;
  }
  if (draft.custodian.trim().length > CUSTODIAN_MAX) {
    errors.custodian = `Keep this under ${CUSTODIAN_MAX} characters.`;
  }
  return errors;
};
