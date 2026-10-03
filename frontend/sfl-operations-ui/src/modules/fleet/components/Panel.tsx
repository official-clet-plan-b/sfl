import { ReactNode } from 'react';
import {
  Card,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
} from '@rfdtech/components';
import { cn } from 'shared/components/cn';

interface PanelProps {
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
  /**
   * Wraps the card in its own `PageSection`. Turn off for a panel that sits in a grid already
   * wrapped in one - adjacent sections add a top margin, which would push the second column of a
   * row down.
   */
  section?: boolean;
  children?: ReactNode;
}

/**
 * One block of a page: a bordered `Card` with its `SectionHeader`, inside a `PageSection`.
 *
 * The section header's page-title size is too large for a block inside a page, so the card sets the
 * header's own size and spacing tokens rather than restyling its parts.
 */
const Panel = ({
  title,
  description,
  actions,
  className,
  section = true,
  children,
}: PanelProps) => {
  const card = (
    <Card bordered className={cn('h-full min-w-0', !section && className)}>
      {title && (
        <SectionHeader className="[--clet-section-header-margin-bottom:16px] [--clet-section-header-title-size:20px]">
          <SectionTitle>{title}</SectionTitle>
          {description && <SectionDescription>{description}</SectionDescription>}
          {actions && <SectionActions className="items-end [&_button]:whitespace-nowrap">{actions}</SectionActions>}
        </SectionHeader>
      )}
      {children}
    </Card>
  );
  return section ? <PageSection className={className}>{card}</PageSection> : card;
};

export default Panel;
