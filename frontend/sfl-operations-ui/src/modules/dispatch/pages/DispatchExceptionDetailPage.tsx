import { useState } from "react";
import { useNavigate, useParams } from "react-router";
import { Button, PageSection } from "@rfdtech/components";
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  ChevronRight,
  ClipboardList,
  Lock,
  Package,
  Pencil,
  Play,
  RefreshCw,
  Square,
  UserPlus,
  X,
  type LucideIcon,
} from "lucide-react";
import {
  EXCEPTION_TYPE_DESCRIPTIONS,
  ExceptionType,
} from "modules/dispatch/api/enums";
import {
  ExceptionAction,
  dispatchExceptionsApi,
} from "modules/dispatch/api/dispatchApi";
import {
  EXCEPTION_RULES,
  exceptionActionAllowed,
  exceptionClosureBlockers,
  exceptionOpen,
  exceptionSlaBreached,
} from "modules/dispatch/api/workflow";
import { ExceptionActionDialog } from "modules/dispatch/dialogs/exceptionDialogs";
import { formatDueIn, siteOf } from "modules/fuel/components/fuelFormat";
import { humanise } from "modules/fleet/api/enums";
import { Callout } from "modules/dispatch/components/formKit";
import PageHeading from "modules/dispatch/components/PageHeading";
import Panel from "modules/dispatch/components/Panel";
import StatusBadge from "modules/dispatch/components/StatusBadge";
import DataState from "shared/components/DataState";
import KeyValueGrid from "shared/components/KeyValueGrid";
import { useNotifier } from "shared/components/Notifier";
import { formatDateTime } from "shared/components/format";
import { useApiQuery } from "shared/hooks/useApiQuery";
import { dispatchPaths } from "shared/layout/navigation";

/** One sentence per action, naming what landed. */
const CONFIRMATIONS: Record<ExceptionAction, string> = {
  assign: "Case assigned. The assignee has been notified.",
  reassign: "Case reassigned. The new assignee has been notified.",
  review: "Review started.",
  "request-explanation":
    "Explanation requested. The case is now awaiting a response.",
  explain: "Explanation recorded.",
  approve: "Approved. The case still has to be closed.",
  reject: "Rejected. The case still has to be closed.",
  escalate: "Case escalated.",
  hold: "Case placed on hold.",
  resume: "Case resumed.",
  cancel: "Case cancelled.",
  close: "Case closed. The manifest it was blocking can now close.",
  reopen: "Case reopened. It blocks its manifest again.",
};

const ACTION_ORDER: ExceptionAction[] = [
  "assign",
  "reassign",
  "review",
  "request-explanation",
  "explain",
  "approve",
  "reject",
  "close",
  "hold",
  "resume",
  "escalate",
  "reopen",
  "cancel",
];

/** Actions the service takes no input for, so they run from the button rather than a dialog. */
const NO_INPUT_ACTIONS: ExceptionAction[] = [
  "review",
  "request-explanation",
  "resume",
];

/**
 * A dispatch exception case, and every action legal from where it stands.
 *
 * The thing that makes this queue different from the fuel one is what an open case *does*: it blocks
 * the manifest it belongs to from closing. So the screen leads with which consignment is held up,
 * and closure - the action that releases it - is the one the layout points at.
 */
const DispatchExceptionDetailPage = () => {
  const { caseId = "" } = useParams();
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();
  const [dialog, setDialog] = useState<ExceptionAction | null>(null);
  const [working, setWorking] = useState<ExceptionAction | null>(null);

  const exceptionCase = useApiQuery(
    (signal) => dispatchExceptionsApi.findById(caseId, signal),
    [caseId],
  );

  const runDirect = async (action: ExceptionAction) => {
    setWorking(action);
    try {
      await dispatchExceptionsApi.transition(caseId, action, {
        value: null,
        evidenceId: null,
      });
      notifySuccess(CONFIRMATIONS[action]);
      exceptionCase.refetch();
    } catch (error) {
      notifyError(error);
    } finally {
      setWorking(null);
    }
  };

  const record = exceptionCase.data;
  const closureBlockers = record ? exceptionClosureBlockers(record) : [];
  const breached = record ? exceptionSlaBreached(record) : false;

  return (
    <>
      <PageHeading
        title={record?.exceptionNumber ?? "Exception case"}
        subtitle={record ? humanise(record.type) : undefined}
        crumbs={[
          { label: "Dispatch", to: dispatchPaths.dashboard },
          { label: "Exception cases", to: dispatchPaths.exceptions },
          { label: record?.exceptionNumber ?? "…" },
        ]}
        actions={
          <Button
            variant="outline"
            onClick={() => navigate(dispatchPaths.exceptions)}
          >
            <ArrowLeft size={14} strokeWidth={1.5} aria-hidden="true" />
            Queue
          </Button>
        }
        meta={
          record && (
            <>
              <StatusBadge value={record.status} />
              <StatusBadge value={record.severity} />
              {record.securityRelevant && (
                <StatusBadge
                  value="SECRET"
                  label="Security relevant"
                  tone="blocked"
                />
              )}
              {record.escalationLevel > 0 && (
                <StatusBadge
                  value="ESCALATED"
                  label={`Escalation level ${record.escalationLevel}`}
                  tone="blocked"
                />
              )}
            </>
          )
        }
      />

      <DataState
        loading={exceptionCase.initialising}
        error={exceptionCase.error}
        onRetry={exceptionCase.refetch}
        minHeight={300}
      >
        {record && (
          <>
            <PageSection className="space-y-3 empty:hidden">
              {record.dispatchId && exceptionOpen(record) && (
                <Callout
                  tone="warning"
                  title="This case is blocking a consignment"
                  action={
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        navigate(
                          dispatchPaths.manifestDetail(
                            record.dispatchId as string,
                          ),
                        )
                      }
                    >
                      Open the manifest
                      <ChevronRight
                        size={14}
                        strokeWidth={1.5}
                        aria-hidden="true"
                      />
                    </Button>
                  }
                >
                  The manifest it belongs to cannot be closed while this case is
                  open. Closing the case is what releases it.
                </Callout>
              )}
              {breached && (
                <Callout tone="danger" title="This case has breached its SLA">
                  It was due {formatDateTime(record.slaDueAt)} -{" "}
                  {formatDueIn(record.slaDueAt)}.
                </Callout>
              )}
              {record.securityRelevant && exceptionOpen(record) && (
                <Callout tone="warning" title="This case is security relevant">
                  Escalating it surfaces the case to the security function as
                  well as to the dispatch manager.
                </Callout>
              )}
              {record.status === "CLOSED" && (
                <Callout tone="success" title="This case is closed">
                  {record.closureReason ?? "No closure reason was recorded."}
                </Callout>
              )}
              {record.status === "CANCELLED" && (
                <Callout tone="info" title="This case is cancelled">
                  {record.closureReason ?? "No reason was recorded."}
                </Callout>
              )}
            </PageSection>

            <Panel title="Actions">
              <div className="flex flex-wrap items-center gap-2">
                {ACTION_ORDER.filter((action) =>
                  exceptionActionAllowed(record, action),
                ).map((action) => {
                  const ActionIcon = NO_INPUT_ACTIONS.includes(action)
                    ? Play
                    : buttonIcon(action);
                  return (
                    <Button
                      key={action}
                      variant={
                        NO_INPUT_ACTIONS.includes(action)
                          ? "outline"
                          : buttonVariant(action)
                      }
                      loading={
                        NO_INPUT_ACTIONS.includes(action)
                          ? working === action
                          : undefined
                      }
                      onClick={() =>
                        NO_INPUT_ACTIONS.includes(action)
                          ? runDirect(action)
                          : setDialog(action)
                      }
                    >
                      <ActionIcon
                        size={14}
                        strokeWidth={1.5}
                        aria-hidden="true"
                      />
                      {EXCEPTION_RULES[action].label}
                    </Button>
                  );
                })}
              </div>

              <div className="mt-4 flex flex-wrap items-center gap-2">
                {record.dispatchId && (
                  <Button
                    variant="ghost"
                    onClick={() =>
                      navigate(
                        dispatchPaths.manifestDetail(
                          record.dispatchId as string,
                        ),
                      )
                    }
                  >
                    <ClipboardList
                      size={14}
                      strokeWidth={1.5}
                      aria-hidden="true"
                    />
                    Manifest
                    <ChevronRight
                      size={14}
                      strokeWidth={1.5}
                      aria-hidden="true"
                    />
                  </Button>
                )}
                {record.courierItemId && (
                  <Button
                    variant="ghost"
                    onClick={() =>
                      navigate(
                        dispatchPaths.itemDetail(
                          record.courierItemId as string,
                        ),
                      )
                    }
                  >
                    <Package size={14} strokeWidth={1.5} aria-hidden="true" />
                    Courier item
                    <ChevronRight
                      size={14}
                      strokeWidth={1.5}
                      aria-hidden="true"
                    />
                  </Button>
                )}
              </div>
            </Panel>

            <div className="mt-6 grid gap-6 xl:grid-cols-[1.4fr_1fr]">
              <div className="space-y-6">
                <Panel title="Case">
                  <KeyValueGrid
                    items={[
                      { label: "Case number", value: record.exceptionNumber },
                      { label: "Site", value: siteOf(record.siteCode) },
                      { label: "Type", value: humanise(record.type) },
                      { label: "Severity", value: humanise(record.severity) },
                      {
                        label: "Security relevant",
                        value: record.securityRelevant ? "Yes" : "No",
                      },
                      {
                        label: "Assignee",
                        value: record.assignee ?? "Unassigned",
                      },
                      {
                        label: "SLA due",
                        value: formatDateTime(record.slaDueAt),
                      },
                      {
                        label: "SLA standing",
                        value: formatDueIn(record.slaDueAt),
                      },
                      {
                        label: "Escalation level",
                        value: record.escalationLevel,
                      },
                      {
                        label: "Occurrence key",
                        value: record.occurrenceKey,
                        span: 2,
                      },
                      {
                        label: "Explanation",
                        value: record.explanation ?? "-",
                        span: 2,
                      },
                      {
                        label: "Decision",
                        value: record.decision
                          ? humanise(record.decision)
                          : "-",
                      },
                      {
                        label: "Evidence reference",
                        value: record.evidenceId ?? "-",
                      },
                      {
                        label: "Closure reason",
                        value: record.closureReason ?? "-",
                        span: 2,
                      },
                    ]}
                  />
                </Panel>

                <Panel title="Why this case exists">
                  <p className="text-sm text-foreground">
                    {EXCEPTION_TYPE_DESCRIPTIONS[
                      record.type as ExceptionType
                    ] ??
                      "This type is recorded by the service but is not described here."}
                  </p>
                  {record.detectedRules.length > 0 && (
                    <ul className="mt-3 space-y-2.5">
                      {record.detectedRules.map((rule) => (
                        <li
                          key={rule}
                          className="rounded-md border border-border px-3.5 py-2.5"
                        >
                          <p className="text-sm font-semibold text-foreground">
                            {humanise(rule)}
                          </p>
                        </li>
                      ))}
                    </ul>
                  )}
                  <p className="mt-3 text-xs text-muted-foreground">
                    The occurrence key is stable per detection, so a repeated
                    detection updates this case rather than raising a second
                    one.
                  </p>
                </Panel>
              </div>

              <div className="space-y-6">
                <Panel
                  title="Path to closure"
                  description="All three are required before the case can be closed"
                >
                  <ul className="space-y-3">
                    <ClosureStep
                      label="Explanation recorded"
                      satisfied={Boolean(record.explanation)}
                      hint="Request one, then record the response."
                    />
                    <ClosureStep
                      label="Decision recorded"
                      satisfied={Boolean(record.decision)}
                      hint="Approve or reject the case from under review."
                    />
                    <ClosureStep
                      label="Closure evidence"
                      satisfied={record.status === "CLOSED"}
                      hint="Supplied with the closure itself."
                    />
                  </ul>
                  {exceptionOpen(record) && closureBlockers.length === 0 && (
                    <Callout tone="success" className="mt-4">
                      Everything the service needs is recorded. Closure needs an
                      evidence reference.
                    </Callout>
                  )}
                </Panel>

                <Panel title="Provenance">
                  <KeyValueGrid
                    columns={2}
                    items={[
                      {
                        label: "Raised by",
                        value: record.metadata.createdBy ?? "-",
                      },
                      {
                        label: "Raised at",
                        value: formatDateTime(record.metadata.createdAt),
                      },
                      {
                        label: "Last change by",
                        value: record.metadata.lastModifiedBy ?? "-",
                      },
                      {
                        label: "Last change at",
                        value: formatDateTime(record.metadata.lastModifiedAt),
                      },
                      {
                        label: "Record version",
                        value: record.metadata.version,
                      },
                      {
                        label: "Correlation ID",
                        value: record.metadata.auditCorrelationId ?? "-",
                        span: 2,
                      },
                    ]}
                  />
                  <p className="mt-3 text-xs text-muted-foreground">
                    The dispatch module exposes no per-record transition
                    history, so this is the case’s own provenance rather than
                    its audit trail.
                  </p>
                </Panel>

                <Panel title="Where this can go next">
                  <ul className="space-y-2.5">
                    {ACTION_ORDER.map((action) => {
                      const allowed = exceptionActionAllowed(record, action);
                      const rule = EXCEPTION_RULES[action];
                      const MarkIcon = allowed ? CheckCircle2 : X;
                      return (
                        <li key={action} className="flex items-start gap-2.5">
                          <MarkIcon
                            size={15}
                            className={
                              allowed
                                ? "mt-0.5 shrink-0 text-success"
                                : "mt-0.5 shrink-0 text-muted-foreground"
                            }
                            aria-hidden="true"
                          />
                          <div className="min-w-0">
                            <p
                              className={
                                allowed
                                  ? "text-sm font-medium text-foreground"
                                  : "text-sm text-muted-foreground"
                              }
                            >
                              {rule.label}
                              {rule.privileged && (
                                <span className="ml-1.5 text-xs font-semibold text-warning-text">
                                  privileged
                                </span>
                              )}
                            </p>
                            <p className="text-xs text-muted-foreground">
                              {allowed
                                ? `Needs ${rule.permission}.`
                                : rule.from.length === 0
                                  ? "Available from any state."
                                  : `From ${rule.from
                                      .map((state) =>
                                        humanise(state).toLowerCase(),
                                      )
                                      .join(", ")}.`}
                            </p>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </Panel>
              </div>
            </div>

            {dialog && (
              <ExceptionActionDialog
                open
                exceptionCase={record}
                action={dialog}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess(CONFIRMATIONS[dialog]);
                  exceptionCase.refetch();
                }}
              />
            )}
          </>
        )}
      </DataState>
    </>
  );
};

const ClosureStep = ({
  label,
  satisfied,
  hint,
}: {
  label: string;
  satisfied: boolean;
  hint: string;
}) => {
  const StepIcon = satisfied ? CheckCircle2 : AlertCircle;
  return (
    <li className="flex items-start gap-2.5">
      <StepIcon
        size={16}
        className={
          satisfied
            ? "mt-0.5 shrink-0 text-success"
            : "mt-0.5 shrink-0 text-warning"
        }
        aria-hidden="true"
      />
      <div className="min-w-0">
        <p className="text-sm font-medium text-foreground">{label}</p>
        {!satisfied && <p className="text-xs text-muted-foreground">{hint}</p>}
      </div>
    </li>
  );
};

const buttonVariant = (action: ExceptionAction) => {
  switch (action) {
    case "assign":
      return "primary" as const;
    case "close":
      return "secondary" as const;
    case "cancel":
    case "reject":
      return "destructive" as const;
    default:
      return "outline" as const;
  }
};

const buttonIcon = (action: ExceptionAction): LucideIcon => {
  switch (action) {
    case "assign":
    case "reassign":
      return UserPlus;
    case "explain":
      return Pencil;
    case "approve":
      return CheckCircle2;
    case "reject":
      return X;
    case "escalate":
      return AlertTriangle;
    case "hold":
      return Square;
    case "close":
      return Lock;
    case "reopen":
      return RefreshCw;
    case "cancel":
      return X;
    default:
      return Play;
  }
};

export default DispatchExceptionDetailPage;
