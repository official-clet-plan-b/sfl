import {
  Badge,
  Button,
  EmptyState,
  Modal,
  ModalBody,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  ModalPortal,
  ModalTitle,
  Table,
  TableContent,
  type TableColumn,
} from '@rfdtech/components';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { changeLabel, changeSource, describeChange } from '../api/assets';
import { assetVisibilityApi, type AssetHistoryEntry, type AssetReference } from '../api/phase2Api';

interface AssetHistoryDialogProps {
  asset: AssetReference;
  onClose: () => void;
}

const columns: TableColumn<AssetHistoryEntry>[] = [
  {
    id: 'when',
    header: 'When',
    cell: ({ row }) => new Date(row.occurredAt).toLocaleString(),
  },
  {
    id: 'change',
    header: 'Change',
    cell: ({ row }) => <Badge variant="outline">{changeLabel(row.changeType)}</Badge>,
  },
  { id: 'detail', header: 'Detail', cell: ({ row }) => describeChange(row) },
  { id: 'by', header: 'By', cell: ({ row }) => changeSource(row) },
];

/** Who had the asset, and where it was, over time - newest change first. */
const AssetHistoryDialog = ({ asset, onClose }: AssetHistoryDialogProps) => {
  const history = useApiQuery((signal) => assetVisibilityApi.history(asset.id, signal), [asset.id]);

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalPortal>
        <ModalOverlay />
        <ModalContent size="2xl">
          <ModalHeader>
            <ModalTitle>History of {asset.assetCode}</ModalTitle>
            <ModalDescription>
              {asset.name}. Every location, custody and tag change, newest first.
            </ModalDescription>
          </ModalHeader>
          <ModalBody>
            {history.error ? (
              <EmptyState title="History is unavailable" description={history.error.message} />
            ) : (
              <Table paramPrefix="asset-history" variant="soft">
                <TableContent
                  columns={columns}
                  data={history.data ?? []}
                  loading={history.loading}
                  rowKey={(row) => row.id}
                  emptyContent={<EmptyState title="No changes recorded yet" />}
                />
              </Table>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="outline" onClick={onClose}>
              Close
            </Button>
          </ModalFooter>
        </ModalContent>
      </ModalPortal>
    </Modal>
  );
};

export default AssetHistoryDialog;
