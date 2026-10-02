import { ReactNode } from 'react';
import { MapPin, Pencil, X } from 'lucide-react';
import type { ControlState } from 'shared/components/ControlButton';
import ControlButton from './ControlButton';

/**
 * The controls that belong to one register row.
 *
 * <p>Right-aligned in the last column, and a plain wrapper rather than the table's kebab menu: with
 * two or three actions a menu costs a click to discover what is behind it, and the actions here -
 * edit, retire - are the ones an operator came to the register to use.
 *
 * <p>The click is stopped here because `TableContent` puts its row handler on the `<tr>`, so a click
 * on an action would otherwise bubble up and open the row it belongs to.
 */
const RowActions = ({ children }: { children: ReactNode }) => (
  <div className="flex items-center justify-end gap-1.5" onClick={(event) => event.stopPropagation()}>
    {children}
  </div>
);

interface RowActionProps {
  state: ControlState;
  onClick: () => void;
  /** Names the control for assistive technology, and is the tooltip on the icon-only ones. */
  label: string;
  /**
   * `sm` in a table row, `md` in a page header.
   *
   * The same action has to read as the same action in both places, so the detail screens use these
   * components too rather than re-styling a plain button and drifting away from the registers.
   */
  size?: 'sm' | 'md';
}

/**
 * Edit, as a pencil and nothing else.
 *
 * <p>The word "Edit" beside a pencil is the word twice. In a row that already carries a code, a
 * name, a status and a date, a repeated label is the thing the eye has to skip past to reach what
 * the row is actually saying - so the glyph carries it, and the accessible name and the tooltip
 * carry the rest.
 */
export const EditRowAction = ({ state, onClick, label, size = 'sm' }: RowActionProps) => (
  <ControlButton state={state} variant="ghost" size={size} onClick={onClick} aria-label={label}>
    <Pencil size={14} strokeWidth={1.5} aria-hidden="true" />
  </ControlButton>
);

/**
 * Move an asset to another space.
 *
 * <p>A pin, for the same reason edit is a pencil: the row is already dense and the glyph says it.
 */
export const MoveRowAction = ({ state, onClick, label, size = 'sm' }: RowActionProps) => (
  <ControlButton state={state} variant="ghost" size={size} onClick={onClick} aria-label={label}>
    <MapPin size={14} strokeWidth={1.5} aria-hidden="true" />
  </ControlButton>
);

/**
 * Take a record out of a collection - a member out of a zone.
 *
 * <p>Destructive, because it removes something, and icon-only because the row it sits on already
 * names what would be removed. Distinct from retiring: nothing is archived here, a membership simply
 * ends, and the button directly above puts it back.
 */
export const RemoveRowAction = ({ state, onClick, label, size = 'sm' }: RowActionProps) => (
  <ControlButton
    state={state}
    variant="destructive"
    size={size}
    onClick={onClick}
    aria-label={label}
  >
    <X size={14} strokeWidth={1.5} aria-hidden="true" />
  </ControlButton>
);

/**
 * Retire, in the quiet destructive treatment, because it takes a record out of use.
 *
 * <p>Not `primary-destructive` - a filled red button on every row of a register would make the table
 * read as a page of warnings, and the heavy treatment belongs on the confirmation, which is where
 * the irreversible half of this actually happens.
 */
export const RetireRowAction = ({ state, onClick, label, size = 'sm' }: RowActionProps) => (
  <ControlButton
    state={state}
    variant="destructive"
    size={size}
    onClick={onClick}
    aria-label={label}
  >
    Retire
  </ControlButton>
);

export default RowActions;
