import type { ReactNode } from 'react';
import {
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
} from '@rfdtech/components';

interface TitledSectionProps {
  title: string;
  description?: string;
  subtitle?: string;
  flush?: boolean;
  actions?: ReactNode;
  children: ReactNode;
}

/**
 * One titled block of a detail screen: a `PageSection` opened by its own `SectionHeader`.
 *
 * The detail pages carry five to eight of these each, and the four-element composition is identical
 * every time - this is that composition, not a replacement for any part of it.
 */
const TitledSection = ({ title, description, subtitle, actions, children }: TitledSectionProps) => (
  <PageSection>
    <SectionHeader>
      <SectionTitle>{title}</SectionTitle>
      {(description ?? subtitle) && <SectionDescription>{description ?? subtitle}</SectionDescription>}
      {actions && <SectionActions className="items-end [&_button]:whitespace-nowrap">{actions}</SectionActions>}
    </SectionHeader>
    {children}
  </PageSection>
);

export default TitledSection;
