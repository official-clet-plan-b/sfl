import type { ReactNode } from 'react';
import {
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  useBreadcrumbs,
} from '@rfdtech/components';

export interface Crumb {
  label: string;
  to?: string;
}

interface PageHeadingProps {
  title: string;
  subtitle?: string;
  /** Published to the shell's header, which draws the trail - the page does not render it. */
  crumbs?: Crumb[];
  actions?: ReactNode;
  /** Status badges or other context that belongs with the title rather than in the body. */
  meta?: ReactNode;
}

/**
 * Where am I, what is this, what can I do from here: the library's `SectionHeader` for the title,
 * description and actions, and the shell's breadcrumb trail for the first.
 */
const PageHeading = ({ title, subtitle, crumbs, actions, meta }: PageHeadingProps) => {
  useBreadcrumbs((crumbs ?? []).map((crumb) => ({ label: crumb.label, href: crumb.to })));

  return (
    <PageSection>
      <SectionHeader>
        <SectionTitle>{title}</SectionTitle>
        {subtitle && <SectionDescription>{subtitle}</SectionDescription>}
        {/* items-end: a labelled control (the site select) is taller than a bare button, so the buttons sit on the control's bottom edge rather than level with its label. */}
        {actions && <SectionActions className="items-end [&_button]:whitespace-nowrap">{actions}</SectionActions>}
      </SectionHeader>
      {meta && <div className="-mt-3 flex flex-wrap items-center gap-2">{meta}</div>}
    </PageSection>
  );
};

export default PageHeading;
