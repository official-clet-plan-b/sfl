import { useState } from 'react';
import { Download } from 'lucide-react';
import { Button } from '@rfdtech/components';
import { downloadFile } from 'shared/api/client';
import { permits } from 'shared/layout/actorPermissions';
import ReasonDialog from './ReasonDialog';
import { useNotifier } from './Notifier';

interface ExportButtonProps {
  /** The module's export path, for example `/api/v1/facilities/leases/exports/agreements`. */
  path: string;
  siteCode: string;
  label?: string;
}

/**
 * Takes a register out as a CSV file. Offered only to roles holding the export grant, and never without a stated
 * reason: the service refuses a short one, watermarks the file with who took it and why, and audits the export. What the
 * file contains is what the signed-in role may read, so a masked column stays masked.
 */
const ExportButton = ({ path, siteCode, label = 'Export' }: ExportButtonProps) => {
  const notifier = useNotifier();
  const [open, setOpen] = useState(false);
  if (!permits('FACILITIES_REGISTER_EXPORT')) return null;
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)} disabled={!siteCode}><Download size={14} strokeWidth={1.5} aria-hidden /> {label}</Button>
      {open && (
        <ReasonDialog title="Export this register" description={`Downloads the register for ${siteCode} as a CSV. The file is watermarked with your name, the time and your reason, and the export is audited. It contains only what your role may read.`}
          label="Why you need this file" submitLabel="Download" minimum={10}
          write={async (reason) => { await downloadFile(path, { siteCode, reason }, 'export.csv', 'text/csv, application/json', 'facilities'); notifier.notifySuccess('Export downloaded'); }}
          onClose={() => setOpen(false)} onDone={() => setOpen(false)} />
      )}
    </>
  );
};

export default ExportButton;
