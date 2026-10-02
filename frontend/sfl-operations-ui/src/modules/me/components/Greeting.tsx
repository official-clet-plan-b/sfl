import { HeroBanner, PageSection } from '@rfdtech/components';
import { sflActor } from 'shared/api/config';

/** The greeting each personal landing opens with, addressed to the signed-in actor. */
const Greeting = () => (
  <PageSection>
    <HeroBanner name={sflActor.displayName || 'Welcome'} />
  </PageSection>
);

export default Greeting;
