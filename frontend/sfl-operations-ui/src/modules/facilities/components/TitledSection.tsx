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
  actions?: ReactNode;
  children: ReactNode;
}

/**
 * One titled block of a detail screen: a `PageSection` opened by its own `SectionHeader`.
 *
 * The detail pages carry five to eight of these each, and the four-element composition is identical
 * every time - this is that composition, not a replacement for any part of it.
 */
const TitledSection = ({ title, description, actions, children }: TitledSectionProps) => (
  <PageSection>
    <SectionHeader>
      <SectionTitle>{title}</SectionTitle>
      {description && <SectionDescription>{description}</SectionDescription>}
      {actions && <SectionActions>{actions}</SectionActions>}
    </SectionHeader>
    {children}
  </PageSection>
);

export default TitledSection;
