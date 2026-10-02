import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import IfimpValue from './IfimpValue';

describe('IfimpValue', () => {
  it('renders nested records with labels instead of raw JSON', () => {
    const { container } = render(<IfimpValue value={{ system: 'S159', name: 'Room booking', available: true }} />);

    expect(screen.getByText('System')).toBeInTheDocument();
    expect(screen.getByText('Room booking')).toBeInTheDocument();
    expect(screen.getByText('Yes')).toBeInTheDocument();
    expect(container.textContent).not.toContain('{');
  });

  it('renders object arrays as individual readable cards', () => {
    render(<IfimpValue value={[{ name: 'Room booking', available: true }, { name: 'Catering', available: false }]} />);

    expect(screen.getByText('Room booking')).toBeInTheDocument();
    expect(screen.getByText('Catering')).toBeInTheDocument();
    expect(screen.getByText('No')).toBeInTheDocument();
  });

  it('names empty collections instead of showing brackets', () => {
    render(<IfimpValue value={[]} />);
    expect(screen.getByText('None')).toBeInTheDocument();
  });
});
