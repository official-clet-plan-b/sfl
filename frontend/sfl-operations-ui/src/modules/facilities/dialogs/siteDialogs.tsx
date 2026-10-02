import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';
import type { CreateSiteRequest, Site, UpdateSiteRequest } from '../api/dto';
import { StaleWriteNotice } from './common';
import { FormDialog, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

/**
 * Creating and editing a site.
 *
 * A site is a CLET centre, and it is the root of everything else in the estate: buildings hang off
 * it, spaces off those, and every asset, zone, device and readiness record carries its code. That is
 * why the code cannot be edited afterwards and the name can - the code is what every other record
 * refers to it by, and the name is only what people call it.
 *
 * Field lengths mirror `FacilitiesRequests.CreateSite` exactly, which in turn mirrors the column
 * widths in V2. Where the two ever disagree the database wins, so a client rule that is looser would
 * let an operator type 200 characters and be refused at the constraint with no field to blame.
 */

interface RegisterSiteDialogProps {
  onClose: () => void;
  onSubmit: (request: CreateSiteRequest) => Promise<void>;
}

export const RegisterSiteDialog = ({ onClose, onSubmit }: RegisterSiteDialogProps) => {
  const form = useFleetForm({
    initialValues: { siteCode: '', name: '', description: '' },
    schema: {
      siteCode: compose(required('Site code'), maxLength('Site code', 40)),
      name: compose(required('Name'), maxLength('Name', 160)),
      description: maxLength('Description', 1000),
    },
    onSubmit: (values) =>
      onSubmit({
        siteCode: values.siteCode.trim(),
        name: values.name.trim(),
        description: values.description.trim() || null,
      }),
  });

  return (
    <FormDialog
      open
      title="Add a site"
      description="A CLET centre, and the root every other estate record hangs off"
      submitLabel="Add the site"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Site code"
            value={form.values.siteCode}
            onChange={(value) => form.setValue('siteCode', value)}
            required
            maxLength={40}
            placeholder="CLET-HQ"
            {...form.fieldProps('siteCode', 'Unique, and permanent once saved.')}
          />
          <TextInput
            label="Name"
            value={form.values.name}
            onChange={(value) => form.setValue('name', value)}
            required
            maxLength={160}
            placeholder="CLET Headquarters"
            {...form.fieldProps('name')}
          />
        </div>

        <TextAreaInput
          label="Description"
          value={form.values.description}
          onChange={(value) => form.setValue('description', value)}
          rows={3}
          maxLength={1000}
          placeholder="Where it is, what it runs, anything an operator arriving cold should know."
          {...form.fieldProps('description')}
        />

      </div>
    </FormDialog>
  );
};

interface EditSiteDialogProps {
  site: Site;
  onClose: () => void;
  onSubmit: (request: UpdateSiteRequest) => Promise<void>;
}

/**
 * Editing a site.
 *
 * `expectedVersion` is sent from the record this dialog was opened on, so a colleague saving first
 * turns this into a `409` instead of quietly overwriting them. The site code is shown and not
 * offered: `UpdateSite` has no field for it, so an input would be a promise the service does not
 * keep.
 */
export const EditSiteDialog = ({ site, onClose, onSubmit }: EditSiteDialogProps) => {
  const form = useFleetForm({
    initialValues: { name: site.name, description: site.description ?? '' },
    schema: {
      name: compose(required('Name'), maxLength('Name', 160)),
      description: maxLength('Description', 1000),
    },
    onSubmit: (values) =>
      onSubmit({
        name: values.name.trim(),
        description: values.description.trim() || null,
        expectedVersion: site.metadata.version,
      }),
  });

  return (
    <FormDialog
      open
      title={`Edit ${site.siteCode}`}
      description="The name and description. The code and the operating mode are changed elsewhere."
      submitLabel="Save changes"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <TextInput
          label="Name"
          value={form.values.name}
          onChange={(value) => form.setValue('name', value)}
          required
          maxLength={160}
          {...form.fieldProps('name')}
        />

        <TextAreaInput
          label="Description"
          value={form.values.description}
          onChange={(value) => form.setValue('description', value)}
          rows={3}
          maxLength={1000}
          {...form.fieldProps('description')}
        />

        <StaleWriteNotice error={form.formError} />
      </div>
    </FormDialog>
  );
};
