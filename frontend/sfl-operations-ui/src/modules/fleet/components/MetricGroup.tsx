import { ReactNode } from 'react';
import { Card, MetricCards, PageSection } from '@rfdtech/components';

/**
 * The grey group the design puts a row of `MetricCard variant="soft"` in: one rounded surface
 * holding the cards, so the figures read as a set rather than as four unrelated tiles.
 */
const MetricGroup = ({ children }: { children: ReactNode }) => (
  <PageSection>
    <Card className="bg-[var(--clet-surface-subtle)]">
      <MetricCards>{children}</MetricCards>
    </Card>
  </PageSection>
);

export default MetricGroup;
