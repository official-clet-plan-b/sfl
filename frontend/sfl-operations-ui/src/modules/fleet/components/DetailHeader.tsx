import { ReactNode } from 'react';
import {
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  useBreadcrumbs,
} from '@rfdtech/components';

interface DetailHeaderProps {
  title: string;
  subtitle?: string;
  /** The trail above the page. The last entry is the page itself and carries no link. */
  crumbs: { label: string; to?: string }[];
  actions?: ReactNode;
  /** Status badges and the like, under the title. */
  meta?: ReactNode;
}

/**
 * A record page's header: where am I (breadcrumbs), what is this, what can I do from here.
 *
 * The breadcrumbs go to the shell's header through `useBreadcrumbs`; the title block is the
 * library's `SectionHeader`.
 */
const DetailHeader = ({ title, subtitle, crumbs, actions, meta }: DetailHeaderProps) => {
  useBreadcrumbs(crumbs.map((crumb) => ({ label: crumb.label, href: crumb.to })));
  return (
    <PageSection>
      <SectionHeader>
        <SectionTitle>{title}</SectionTitle>
        {subtitle && <SectionDescription>{subtitle}</SectionDescription>}
        {actions && <SectionActions className="items-end [&_button]:whitespace-nowrap">{actions}</SectionActions>}
      </SectionHeader>
      {meta && <div className="mt-3">{meta}</div>}
    </PageSection>
  );
};

export default DetailHeader;
