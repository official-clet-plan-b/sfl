import { useMemo, useState } from 'react';
import { Button, Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { ReadinessChecklist, Space, SubmitAssessmentRequest } from '../api/dto';
import { humaniseCode, severityTone } from '../components/facilitiesFormat';
import { FormDialog, SelectInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';
import StatusBadge from 'modules/facilities/components/StatusBadge';

interface SubmitAssessmentDialogProps {
  space: Space;
  checklists: ReadinessChecklist[];
  onClose: () => void;
  onSubmitted: (request: SubmitAssessmentRequest) => Promise<void>;
}

/**
 * Submitting a readiness assessment - the module's primary field workflow.
 *
 * Two decisions shape it:
 *
 * **Every answer starts unanswered, and an unanswered item counts as failed.** The service treats it
 * that way, so the dialog does too and says so: defaulting to "passed" would let a hall go ready on
 * a form somebody tabbed through, which is exactly the failure a readiness checklist exists to
 * prevent. The submit button stays disabled until every item has been answered either way.
 *
 * **Each item shows what its failure costs** before it is answered. An assessor deciding whether the
 * fire door "really" latches should know that answering no blocks the hall.
 */
const SubmitAssessmentDialog = ({
  space,
  checklists,
  onClose,
  onSubmitted,
}: SubmitAssessmentDialogProps) => {
  const applicable = useMemo(
    () =>
      checklists.filter(
        (checklist) => checklist.spaceType === null || checklist.spaceType === space.spaceType,
      ),
    [checklists, space.spaceType],
  );

  const [checklistId, setChecklistId] = useState<string>(applicable[0]?.id ?? '');
  const [answers, setAnswers] = useState<Record<string, boolean | undefined>>({});
  const [comments, setComments] = useState<Record<string, string>>({});
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const checklist = applicable.find((candidate) => candidate.id === checklistId);
  const items = checklist?.items ?? [];
  const unanswered = items.filter((item) => answers[item.itemCode] === undefined);
  const failing = items.filter((item) => answers[item.itemCode] === false);
  const criticalFailing = failing.filter((item) => item.severityIfFailed === 'CRITICAL');

  const submit = async () => {
    if (!checklist || unanswered.length > 0) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmitted({
        roomId: space.id,
        checklistId: checklist.id,
        answers: items.map((item) => ({
          itemCode: item.itemCode,
          passed: answers[item.itemCode] === true,
          comment: comments[item.itemCode]?.trim() || null,
        })),
        notes: notes.trim() || null,
      });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title="Assess readiness"
      description={`${space.roomCode} - ${space.name}`}
      submitLabel="Submit assessment"
      submitting={submitting}
      submitDisabled={!checklist || unanswered.length > 0}
      formError={formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        {applicable.length === 0 ? (
          <Notice variant="warning" title="No checklist applies to this space">
            No active readiness checklist covers a {humaniseCode(space.spaceType).toLowerCase()} at
            this site. Create one before assessing, or the assessment records no answers.
          </Notice>
        ) : (
          <SelectInput
            label="Checklist"
            value={checklistId}
            onChange={(value) => {
              setChecklistId(value);
              setAnswers({});
              setComments({});
            }}
            required
            options={applicable.map((candidate) => ({
              value: candidate.id,
              label: `${candidate.checklistCode} v${candidate.version} - ${candidate.name}`,
            }))}
            helperText="The most specific applicable checklist is selected by default."
          />
        )}

        {items.length > 0 && (
          <div className="space-y-2">
            {items.map((item) => {
              const answer = answers[item.itemCode];
              return (
                <div
                  key={item.itemCode}
                  className="rounded-lg border border-border p-3"
                >
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-foreground">{item.description}</p>
                      <div className="mt-1 flex flex-wrap items-center gap-2">
                        <span className="text-xs text-muted-foreground">{item.itemCode}</span>
                        <StatusBadge
                          value={item.severityIfFailed}
                          tone={severityTone(item.severityIfFailed)}
                          label={`${humaniseCode(item.severityIfFailed)} if failed`}
                        />
                        {item.mandatory && (
                          <span className="text-xs text-muted-foreground">Mandatory</span>
                        )}
                      </div>
                    </div>
                    {/*
                      Two explicit buttons rather than a checkbox: a checkbox has an unchecked
                      state that reads as "no" and means "not looked at", and those are very
                      different answers on a fire-egress check.
                    */}
                    <div className="flex shrink-0 gap-1.5">
                      <Button
                        size="sm"
                        variant={answer === true ? 'success' : 'outline'}
                        aria-pressed={answer === true}
                        onClick={() =>
                          setAnswers((prev) => ({ ...prev, [item.itemCode]: true }))
                        }
                      >
                        Pass
                      </Button>
                      <Button
                        size="sm"
                        variant={answer === false ? 'primary-destructive' : 'outline'}
                        aria-pressed={answer === false}
                        onClick={() =>
                          setAnswers((prev) => ({ ...prev, [item.itemCode]: false }))
                        }
                      >
                        Fail
                      </Button>
                    </div>
                  </div>

                  {answer === false && (
                    <div className="mt-2.5">
                      <TextAreaInput
                        label="What is wrong"
                        value={comments[item.itemCode] ?? ''}
                        onChange={(value) =>
                          setComments((prev) => ({ ...prev, [item.itemCode]: value }))
                        }
                        rows={2}
                        placeholder="Recorded on the blocker this raises."
                      />
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {unanswered.length > 0 && items.length > 0 && (
          <Notice variant="info">
            {unanswered.length} item{unanswered.length === 1 ? '' : 's'} still to answer. An
            unanswered item counts as failed, so every one must be answered before submitting.
          </Notice>
        )}

        {criticalFailing.length > 0 && (
          <Notice
            variant="error"
            title={`This will block ${space.roomCode}`}
          >
            {criticalFailing.length} critical check{criticalFailing.length === 1 ? '' : 's'} failing.
            The space will be marked BLOCKED and cannot be used until the blockers are resolved.
          </Notice>
        )}

        <TextAreaInput
          label="Assessment notes"
          value={notes}
          onChange={setNotes}
          rows={2}
          placeholder="Anything the checklist does not cover."
        />
      </div>
    </FormDialog>
  );
};

export default SubmitAssessmentDialog;
