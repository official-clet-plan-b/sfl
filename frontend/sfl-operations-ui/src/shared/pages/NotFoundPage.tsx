import { useNavigate } from 'react-router';
import { Button, EmptyState } from '@rfdtech/components';
import { LayoutDashboard, SearchX } from 'lucide-react';
import { landingPath } from 'shared/layout/navigation';

const NotFoundPage = () => {
  const navigate = useNavigate();
  // Back to *this* actor's dashboard, which is not the fleet one for everybody.
  const home = landingPath();

  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <EmptyState
        icon={<SearchX size={26} strokeWidth={1.75} />}
        title="Page not found"
        description="That address is not part of the SFL Operations dashboards. It may have been a link to a record that has since been removed."
        action={
          home ? (
            <Button variant="primary" onClick={() => navigate(home)}>
              <LayoutDashboard size={17} strokeWidth={1.75} aria-hidden="true" />
              Back to the dashboard
            </Button>
          ) : undefined
        }
      />
    </div>
  );
};

export default NotFoundPage;
