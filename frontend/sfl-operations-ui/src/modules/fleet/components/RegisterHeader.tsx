import { ReactNode } from 'react';
import {
  Dropdown,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
} from '@rfdtech/components';
import { sflSites } from 'shared/components/SiteSelect';

interface RegisterHeaderProps {
  title: string;
  description?: string;
  /** The site the screen covers; empty is every site in the actor's scope. Omit for no site picker. */
  siteCode?: string;
  onSiteChange?: (siteCode: string) => void;
  /** Buttons after the site picker. The primary action goes last. */
  actions?: ReactNode;
}

/**
 * A screen's title row: the title on the left, the site it covers and the primary action on the
 * right, which is where the design puts them on every fleet screen.
 */
const RegisterHeader = ({
  title,
  description,
  siteCode,
  onSiteChange,
  actions,
}: RegisterHeaderProps) => (
  <PageSection>
    <SectionHeader>
      <SectionTitle>{title}</SectionTitle>
      {description && <SectionDescription>{description}</SectionDescription>}
      <SectionActions>
        {onSiteChange && (
          <Dropdown
            aria-label="Site"
            value={siteCode || null}
            onValueChange={(value) => onSiteChange(value ?? '')}
            options={sflSites().map((site) => ({ value: site, label: `Site: ${site}` }))}
            placeholder="All sites"
            clearable
          />
        )}
        {actions}
      </SectionActions>
    </SectionHeader>
  </PageSection>
);

export default RegisterHeader;
