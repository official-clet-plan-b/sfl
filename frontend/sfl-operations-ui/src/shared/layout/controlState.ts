export type ControlState =
  | { kind: 'allowed' }
  | { kind: 'hidden' }
  | { kind: 'disabled'; reason: string };

export const allowed: ControlState = { kind: 'allowed' };
export const hidden: ControlState = { kind: 'hidden' };
export const disabled = (reason: string): ControlState => ({ kind: 'disabled', reason });
