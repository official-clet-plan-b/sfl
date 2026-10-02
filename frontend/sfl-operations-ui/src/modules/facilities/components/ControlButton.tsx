import { Button } from '@rfdtech/components';
import type { ButtonProps } from '@rfdtech/components';
import type { ControlState } from 'shared/components/ControlButton';

interface ControlButtonProps extends Omit<ButtonProps, 'disabled' | 'title'> {
  state: ControlState;
}

/**
 * Renders a {@link ControlState} as the button it describes.
 *
 * A permission denial hides the control; a state or data shortfall disables it and says why. S153
 * paid for that distinction - a technician was shown a Close button disabled with "You do not have
 * permission", permanently, on every job - and spelling the rule out at each call site would
 * eventually get it wrong at one of them.
 */
const ControlButton = ({ state, children, ...rest }: ControlButtonProps) => {
  if (state.kind === 'hidden') {
    return null;
  }
  return (
    <Button
      {...rest}
      disabled={state.kind === 'disabled'}
      // The reason travels on the control itself, so it is readable where the operator is looking
      // rather than in a notice further up the page.
      title={state.kind === 'disabled' ? state.reason : undefined}
    >
      {children}
    </Button>
  );
};

export default ControlButton;
