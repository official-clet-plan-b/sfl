/**
 * The booking module's binding of the shared control button.
 *
 * The component moved out of booking when facilities became its second caller. This file stays so
 * the booking call sites keep their import, which is the promotion shape the playbook records for
 * `useClientWindow`: promote the component, leave a thin file behind, touch nothing that was already
 * working.
 */
export { default } from 'modules/facilities/components/ControlButton';
