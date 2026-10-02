import type { ReactNode } from 'react';
import {
  Card,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
} from '@rfdtech/components';

interface PanelProps {
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
  children: ReactNode;
}

/**
 * A titled content section: the library's `PageSection` around a `Card`, headed by a `SectionHeader`.
 *
 * The header's title token is brought down from page-title size to a section's, which is the one
 * thing the design changes between the page header and the panels beneath it.
 */
const Panel = ({ title, description, actions, className, children }: PanelProps) => (
  <PageSection className={className}>
    <Card>
      {title && (
        <SectionHeader className="[--clet-section-header-margin-bottom:16px] [--clet-section-header-title-size:20px]">
          <SectionTitle>{title}</SectionTitle>
          {description && <SectionDescription>{description}</SectionDescription>}
          {actions && <SectionActions>{actions}</SectionActions>}
        </SectionHeader>
      )}
      {children}
    </Card>
  </PageSection>
);

export default Panel;
