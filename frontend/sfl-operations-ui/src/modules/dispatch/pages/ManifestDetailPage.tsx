import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  Button,
  EmptyState,
  PageSection,
  Table,
  TableContent,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  type TableColumn,
} from "@rfdtech/components";
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  ChevronRight,
  Lock,
  Plus,
  RefreshCw,
  Route,
  ShieldAlert,
  Truck,
} from "lucide-react";
import {
  CustodyHandover,
  DispatchExceptionCase,
  DispatchManifestItem,
  DispatchReceipt,
  ReturnReconciliation,
} from "modules/dispatch/api/dto";
import { CUSTODY_HOPS, HOP_DESCRIPTIONS } from "modules/dispatch/api/enums";
import {
  custodyApi,
  dispatchExceptionsApi,
  manifestsApi,
  receiptsApi,
  returnsApi,
} from "modules/dispatch/api/dispatchApi";
import {
  exceptionOpen,
  manifestActionAllowed,
  manifestClosureBlockers,
  manifestReceivable,
  manifestReturnReconcilable,
} from "modules/dispatch/api/workflow";
import {
  AddManifestItemDialog,
  AssignTripDialog,
  CloseManifestDialog,
  SealManifestDialog,
} from "modules/dispatch/dialogs/manifestDialogs";
import {
  ConfirmReceiptDialog,
  ReconcileReturnDialog,
  RecordHandoverDialog,
} from "modules/dispatch/dialogs/custodyDialogs";
import { shortId, siteOf } from "modules/fuel/components/fuelFormat";
import { humanise } from "modules/fleet/api/enums";
import CellStack from "modules/dispatch/components/CellStack";
import { Callout } from "modules/dispatch/components/formKit";
import PageHeading from "modules/dispatch/components/PageHeading";
import Panel from "modules/dispatch/components/Panel";
import StatusBadge from "modules/dispatch/components/StatusBadge";
import DataState from "shared/components/DataState";
import KeyValueGrid from "shared/components/KeyValueGrid";
import { useNotifier } from "shared/components/Notifier";
import { ApiQueryState } from "shared/hooks/useApiQuery";
import { formatDateTime, formatNumber } from "shared/components/format";
import { useApiQuery } from "shared/hooks/useApiQuery";
import { fleetPaths } from "shared/layout/navigation";
import { dispatchPaths } from "shared/layout/navigation";
import { canCreateManifests } from "modules/fleet/api/access";

type DialogKey =
  | "addItem"
  | "seal"
  | "assignTrip"
  | "close"
  | "handover"
  | "receipt"
  | "return"
  | null;

/**
 * One consignment, end to end.
 *
 * Custody, receipts and the return leg have no register of their own and no meaning apart from the
 * manifest they belong to, so they are tabs here rather than three more sidebar entries an operator
 * would cross-reference by hand. That is also what makes closure legible: the blockers live in those
 * tabs, and this page can state them all in one place before offering the action.
 */
const ManifestDetailPage = () => {
  const { manifestId = "" } = useParams();
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();
  const [dialog, setDialog] = useState<DialogKey>(null);
  const [working, setWorking] = useState<string | null>(null);
  const [tab, setTab] = useState("items");

  const manifest = useApiQuery(
    (signal) => manifestsApi.findById(manifestId, signal),
    [manifestId],
  );
  const items = useApiQuery(
    (signal) => manifestsApi.items(manifestId, signal),
    [manifestId],
  );
  const handovers = useApiQuery(
    (signal) => custodyApi.handovers(manifestId, signal),
    [manifestId],
  );
  const gaps = useApiQuery(
    (signal) => custodyApi.gaps(manifestId, signal),
    [manifestId],
  );
  const receipts = useApiQuery(
    (signal) => receiptsApi.list(manifestId, signal),
    [manifestId],
  );
  const returns = useApiQuery(
    (signal) => returnsApi.list(manifestId, signal),
    [manifestId],
  );

  const site = manifest.data ? siteOf(manifest.data.siteCode) : "";

  /**
   * Exception cases against this consignment.
   *
   * `GET /exceptions` has no `dispatchId` filter, so the site's cases are fetched and matched here.
   * Recorded as gap 2 - with more open cases than the window holds, a case against this manifest
   * could be missed, which is why the closure panel says where its count came from.
   */
  /**
   * The cases raised against **this** manifest.
   *
   * `dispatchId` reaches the service now, so this is no longer the site's whole exception window
   * sieved down to one consignment - which quietly missed cases whenever the site had more than the
   * window held.
   */
  const exceptions = useApiQuery(
    (signal) =>
      site
        ? dispatchExceptionsApi.search(
            { siteCode: site, dispatchId: manifestId, size: 100 },
            signal,
          )
        : Promise.resolve(undefined),
    [site, manifestId],
  );

  const relatedCases = useMemo(
    () => exceptions.data?.content ?? [],
    [exceptions.data],
  );
  const openCases = useMemo(
    () => relatedCases.filter(exceptionOpen),
    [relatedCases],
  );

  const closureBlockers = manifestClosureBlockers(gaps.data, openCases.length);

  const refreshAll = () => {
    manifest.refetch();
    items.refetch();
    handovers.refetch();
    gaps.refetch();
    receipts.refetch();
    returns.refetch();
    exceptions.refetch();
  };

  const advance = async (action: "dispatch" | "inTransit") => {
    setWorking(action);
    try {
      if (action === "dispatch") {
        await manifestsApi.dispatch(manifestId);
        notifySuccess("Manifest dispatched.");
      } else {
        await manifestsApi.inTransit(manifestId);
        notifySuccess("Manifest marked in transit.");
      }
      refreshAll();
    } catch (error) {
      notifyError(error);
    } finally {
      setWorking(null);
    }
  };

  const record = manifest.data;

  const itemColumns = useMemo<TableColumn<DispatchManifestItem>[]>(
    () => [
      {
        id: "line",
        header: "Line",
        width: 80,
        cell: ({ row }) => (
          <span className="font-semibold text-foreground">
            {row.sequenceNo}
          </span>
        ),
      },
      {
        id: "item",
        header: "Courier item",
        width: 220,
        cell: ({ row }) => (
          <CellStack
            primary={shortId(row.courierItemId)}
            secondary={row.expectedSealId ?? "no seal expected"}
          />
        ),
      },
      {
        id: "quantity",
        header: "Expected",
        width: 110,
        align: "right",
        cell: ({ row }) => formatNumber(row.expectedQuantity),
      },
      {
        id: "returned",
        header: "Return",
        width: 200,
        align: "right",
        cell: ({ row }) => (
          <div className="flex items-center justify-end gap-1.5">
            {row.returnSealState && <StatusBadge value={row.returnSealState} />}
            <StatusBadge value={row.returnStatus} />
          </div>
        ),
      },
      {
        id: "open",
        header: "",
        width: 100,
        align: "right",
        cell: ({ row }) => (
          <Button
            size="sm"
            variant="ghost"
            onClick={() =>
              navigate(dispatchPaths.itemDetail(row.courierItemId))
            }
          >
            Open
            <ChevronRight size={14} strokeWidth={1.5} aria-hidden="true" />
          </Button>
        ),
      },
    ],
    [navigate],
  );

  const handoverColumns = useMemo<TableColumn<CustodyHandover>[]>(
    () => [
      {
        id: "hop",
        header: "Hop",
        width: 200,
        cell: ({ row }) => (
          <CellStack
            primary={humanise(row.hop)}
            secondary={`#${row.sequenceNo}`}
          />
        ),
      },
      {
        id: "custodians",
        header: "Handover",
        width: 260,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.transferringCustodian} → ${row.receivingCustodian}`}
            secondary={formatDateTime(row.occurredAt)}
          />
        ),
      },
      {
        id: "seal",
        header: "Seal",
        width: 110,
        cell: ({ row }) => <StatusBadge value={row.sealState} />,
      },
      {
        id: "count",
        header: "Verified",
        width: 100,
        align: "right",
        cell: ({ row }) =>
          row.verifiedCount === null ? (
            <span className="text-muted-foreground">not counted</span>
          ) : (
            formatNumber(row.verifiedCount)
          ),
      },
      {
        id: "evidence",
        header: "Evidence",
        width: 100,
        align: "center",
        cell: ({ row }) =>
          row.evidenceId ? (
            <StatusBadge value="ACTIVE" label="Held" tone="ready" />
          ) : (
            <span className="text-muted-foreground">-</span>
          ),
      },
      {
        id: "notes",
        header: "Notes",
        width: 220,
        cell: ({ row }) =>
          row.notes ?? <span className="text-muted-foreground">-</span>,
      },
    ],
    [],
  );

  const receiptColumns = useMemo<TableColumn<DispatchReceipt>[]>(
    () => [
      {
        id: "outcome",
        header: "Outcome",
        width: 180,
        cell: ({ row }) => (
          <div className="flex items-center gap-1.5">
            <StatusBadge value={row.outcome} />
            {row.varianceType && (
              <StatusBadge value={row.varianceType} tone="blocked" />
            )}
          </div>
        ),
      },
      {
        id: "counts",
        header: "Counts",
        width: 160,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.verifiedCount} verified`}
            secondary={
              row.expectedCount === null
                ? "no expectation set"
                : `${row.expectedCount} expected`
            }
          />
        ),
      },
      {
        id: "seal",
        header: "Seal",
        width: 150,
        cell: ({ row }) => (
          <div className="flex items-center gap-1.5">
            <StatusBadge value={row.sealState} />
            {!row.sealVerified && (
              <StatusBadge value="WARNING" label="Unverified" tone="caution" />
            )}
          </div>
        ),
      },
      {
        id: "recipient",
        header: "Received by",
        width: 180,
        cell: ({ row }) => (
          <CellStack
            primary={row.recipientName}
            secondary={formatDateTime(row.capturedAt)}
          />
        ),
      },
      {
        id: "capture",
        header: "Capture",
        width: 120,
        align: "right",
        cell: ({ row }) =>
          row.edgeCaptured ? (
            <StatusBadge value="OFFLINE" label="Edge" tone="accent" />
          ) : (
            <span className="text-muted-foreground">Online</span>
          ),
      },
    ],
    [],
  );

  const returnColumns = useMemo<TableColumn<ReturnReconciliation>[]>(
    () => [
      {
        id: "outcome",
        header: "Outcome",
        width: 140,
        cell: ({ row }) => <StatusBadge value={row.outcome} />,
      },
      {
        id: "counts",
        header: "Counts",
        width: 200,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.returnedCount} returned of ${row.expectedCount ?? "-"}`}
            secondary={
              row.shortfall > 0
                ? `${row.shortfall} short`
                : row.extras > 0
                  ? `${row.extras} more than expected`
                  : "counts agree"
            }
          />
        ),
      },
      {
        id: "seals",
        header: "Broken seals",
        width: 130,
        align: "right",
        cell: ({ row }) =>
          row.brokenSeals > 0 ? (
            <span className="font-semibold text-error">{row.brokenSeals}</span>
          ) : (
            <span className="text-muted-foreground">0</span>
          ),
      },
      {
        id: "by",
        header: "Reconciled",
        width: 200,
        cell: ({ row }) => (
          <CellStack
            primary={row.reconciledBy}
            secondary={formatDateTime(row.reconciledAt)}
          />
        ),
      },
      {
        id: "notes",
        header: "Notes",
        width: 220,
        cell: ({ row }) =>
          row.notes ?? <span className="text-muted-foreground">-</span>,
      },
    ],
    [],
  );

  const caseColumns = useMemo<TableColumn<DispatchExceptionCase>[]>(
    () => [
      {
        id: "case",
        header: "Case",
        width: 260,
        cell: ({ row }) => (
          <button
            type="button"
            className="text-left"
            onClick={(event) => {
              event.stopPropagation();
              navigate(dispatchPaths.exceptionDetail(row.id));
            }}
          >
            <CellStack
              primary={`${row.exceptionNumber} · ${humanise(row.type)}`}
              secondary={
                row.detectedRules.map((rule) => humanise(rule)).join(", ") ||
                "no rule recorded"
              }
            />
          </button>
        ),
      },
      {
        id: "severity",
        header: "Severity",
        width: 110,
        cell: ({ row }) => <StatusBadge value={row.severity} />,
      },
      {
        id: "assignee",
        header: "Assignee",
        width: 150,
        cell: ({ row }) =>
          row.assignee ?? (
            <span className="text-muted-foreground">Unassigned</span>
          ),
      },
      {
        id: "status",
        header: "Status",
        width: 160,
        align: "right",
        cell: ({ row }) => <StatusBadge value={row.status} />,
      },
    ],
    [navigate],
  );

  return (
    <>
      <PageHeading
        title={record?.manifestNumber ?? "Manifest"}
        subtitle={
          record ? `${record.route} · ${record.assignedHandler}` : undefined
        }
        crumbs={[
          { label: "Dispatch", to: dispatchPaths.dashboard },
          { label: "Manifests", to: dispatchPaths.manifests },
          { label: record?.manifestNumber ?? "…" },
        ]}
        actions={
          <Button
            variant="outline"
            onClick={() => navigate(dispatchPaths.manifests)}
          >
            <ArrowLeft size={14} strokeWidth={1.5} aria-hidden="true" />
            Register
          </Button>
        }
        meta={
          record && (
            <>
              <StatusBadge value={record.status} />
              <StatusBadge
                value="NEUTRAL"
                label={`${record.itemCount} item${record.itemCount === 1 ? "" : "s"}`}
                tone="neutral"
              />
              <StatusBadge
                value="NEUTRAL"
                label={`${record.sealIds.length} seal${record.sealIds.length === 1 ? "" : "s"}`}
                tone={
                  record.status !== "DRAFT" && record.sealIds.length === 0
                    ? "caution"
                    : "neutral"
                }
              />
              {openCases.length > 0 && (
                <StatusBadge
                  value="BLOCKED"
                  label={`${openCases.length} open case${openCases.length === 1 ? "" : "s"}`}
                  tone="blocked"
                />
              )}
            </>
          )
        }
      />

      <DataState
        loading={manifest.initialising}
        error={manifest.error}
        onRetry={manifest.refetch}
        minHeight={300}
      >
        {record && (
          <>
            {(record.status === "DRAFT" ||
              record.status === "CLOSED" ||
              record.status === "EXCEPTION") && (
              <PageSection>
                {record.status === "DRAFT" && (
                  <Callout tone="info" title="This manifest is still a draft">
                    Items can be added and removed now. Sealing freezes the
                    contents and cannot be undone.
                  </Callout>
                )}
                {record.status === "CLOSED" && (
                  <Callout tone="success" title="This manifest is closed">
                    {record.closureReason ?? "No closure reason was recorded."}
                  </Callout>
                )}
                {record.status === "EXCEPTION" && (
                  <Callout tone="danger" title="This manifest is in exception">
                    Resolve the open cases below before it can continue.
                  </Callout>
                )}
              </PageSection>
            )}

            <Panel title="Actions">
              <div className="flex flex-wrap items-center gap-2">
                {/* State allows it; the grant decides whether this person is offered it. */}
                {manifestActionAllowed(record, "addItem") &&
                  canCreateManifests() && (
                    <Button
                      variant="primary"
                      onClick={() => setDialog("addItem")}
                    >
                      <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                      Add item
                    </Button>
                  )}
                {manifestActionAllowed(record, "seal") && (
                  <Button variant="secondary" onClick={() => setDialog("seal")}>
                    <Lock size={14} strokeWidth={1.5} aria-hidden="true" />
                    Seal manifest
                  </Button>
                )}
                {manifestActionAllowed(record, "assignTrip") && (
                  <Button
                    variant="outline"
                    onClick={() => setDialog("assignTrip")}
                  >
                    <Route size={14} strokeWidth={1.5} aria-hidden="true" />
                    {record.tripId ? "Reassign movement" : "Assign movement"}
                  </Button>
                )}
                {manifestActionAllowed(record, "dispatch") && (
                  <Button
                    variant="primary"
                    loading={working === "dispatch"}
                    onClick={() => advance("dispatch")}
                  >
                    <Truck size={14} strokeWidth={1.5} aria-hidden="true" />
                    Dispatch
                  </Button>
                )}
                {manifestActionAllowed(record, "inTransit") && (
                  <Button
                    variant="outline"
                    loading={working === "inTransit"}
                    onClick={() => advance("inTransit")}
                  >
                    <Route size={14} strokeWidth={1.5} aria-hidden="true" />
                    Mark in transit
                  </Button>
                )}
                <Button variant="outline" onClick={() => setDialog("handover")}>
                  <ShieldAlert size={14} strokeWidth={1.5} aria-hidden="true" />
                  Record handover
                </Button>
                {manifestReceivable(record) && (
                  <Button
                    variant="secondary"
                    onClick={() => setDialog("receipt")}
                  >
                    <CheckCircle2
                      size={14}
                      strokeWidth={1.5}
                      aria-hidden="true"
                    />
                    Confirm receipt
                  </Button>
                )}
                {manifestReturnReconcilable(record) && (
                  <Button variant="outline" onClick={() => setDialog("return")}>
                    <RefreshCw size={14} strokeWidth={1.5} aria-hidden="true" />
                    Reconcile return
                  </Button>
                )}
                {manifestActionAllowed(record, "close") && (
                  <Button
                    variant="secondary"
                    onClick={() => setDialog("close")}
                  >
                    <Lock size={14} strokeWidth={1.5} aria-hidden="true" />
                    Close manifest
                  </Button>
                )}
                {record.tripId && (
                  <Button
                    variant="ghost"
                    onClick={() =>
                      navigate(fleetPaths.tripDetail(record.tripId as string))
                    }
                  >
                    <Route size={14} strokeWidth={1.5} aria-hidden="true" />
                    Trip
                    <ChevronRight
                      size={14}
                      strokeWidth={1.5}
                      aria-hidden="true"
                    />
                  </Button>
                )}
              </div>

              {manifestActionAllowed(record, "close") &&
                closureBlockers.length > 0 && (
                  <Callout
                    tone="warning"
                    title="Closure is blocked"
                    className="mt-4"
                  >
                    <ul className="mt-1 list-disc space-y-1 pl-4">
                      {closureBlockers.map((blocker) => (
                        <li key={blocker}>{blocker}</li>
                      ))}
                    </ul>
                  </Callout>
                )}
            </Panel>

            <div className="mt-6 grid gap-6 xl:grid-cols-[1.4fr_1fr]">
              <Panel title="Consignment">
                <KeyValueGrid
                  items={[
                    { label: "Manifest number", value: record.manifestNumber },
                    { label: "Site", value: siteOf(record.siteCode) },
                    { label: "Route", value: record.route },
                    { label: "Handler", value: record.assignedHandler },
                    {
                      label: "Destination centre",
                      value: record.destinationCentre ?? "-",
                    },
                    {
                      label: "Examination context",
                      value: record.examinationContext ?? "-",
                    },
                    { label: "Items", value: formatNumber(record.itemCount) },
                    {
                      label: "Seals",
                      value:
                        record.sealIds.length > 0
                          ? record.sealIds.join(", ")
                          : "None recorded",
                      span: 2,
                    },
                    {
                      label: "Dispatched at",
                      value: formatDateTime(record.dispatchedAt),
                    },
                    {
                      label: "Received at",
                      value: formatDateTime(record.receivedAt),
                    },
                    {
                      label: "Reconciled at",
                      value: formatDateTime(record.reconciledAt),
                    },
                    {
                      label: "Closure reason",
                      value: record.closureReason ?? "-",
                      span: 2,
                    },
                  ]}
                />
              </Panel>

              <Panel
                title="Chain of custody"
                description="What the policy makes of the chain"
              >
                <DataState
                  loading={gaps.initialising}
                  error={gaps.error}
                  onRetry={gaps.refetch}
                  minHeight={200}
                >
                  {gaps.data && (
                    <>
                      <Callout
                        tone={gaps.data.closable ? "success" : "warning"}
                      >
                        {gaps.data.closable
                          ? "The chain is complete and clean. It does not block closure."
                          : "The chain is not yet closable. The manifest cannot close until it is."}
                      </Callout>

                      {gaps.data.gaps.length > 0 && (
                        <div className="mt-3">
                          <p className="text-xs font-semibold text-muted-foreground">
                            Recorded gaps
                          </p>
                          <ul className="mt-1.5 space-y-1.5">
                            {gaps.data.gaps.map((gap) => (
                              <li
                                key={gap.handoverId + gap.reason}
                                className="flex items-start gap-2"
                              >
                                <AlertCircle
                                  size={14}
                                  className="mt-0.5 shrink-0 text-error"
                                  aria-hidden="true"
                                />
                                <span className="text-sm text-foreground">
                                  <span className="font-medium">
                                    {humanise(gap.reason)}
                                  </span>
                                  {` at ${humanise(gap.hop).toLowerCase()}`}
                                  {Object.keys(gap.detail).length > 0 &&
                                    ` - ${Object.entries(gap.detail)
                                      .map(
                                        ([key, value]) =>
                                          `${humanise(key).toLowerCase()} ${value}`,
                                      )
                                      .join(", ")}`}
                                </span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}

                      <div className="mt-4">
                        <p className="text-xs font-semibold text-muted-foreground">
                          Hops required for closure
                        </p>
                        <ul className="mt-1.5 space-y-1.5">
                          {CUSTODY_HOPS.map((hop) => {
                            const recorded = (handovers.data ?? []).some(
                              (h) => h.hop === hop,
                            );
                            const required =
                              gaps.data!.missingClosureHops.includes(hop) ||
                              recorded;
                            if (!required) {
                              return null;
                            }
                            const HopIcon = recorded
                              ? CheckCircle2
                              : AlertCircle;
                            return (
                              <li key={hop} className="flex items-start gap-2">
                                <HopIcon
                                  size={14}
                                  className={
                                    recorded
                                      ? "mt-0.5 shrink-0 text-success"
                                      : "mt-0.5 shrink-0 text-warning"
                                  }
                                  aria-hidden="true"
                                />
                                <span className="min-w-0 text-sm">
                                  <span
                                    className={
                                      recorded
                                        ? "text-foreground"
                                        : "font-medium text-foreground"
                                    }
                                  >
                                    {humanise(hop)}
                                  </span>
                                  <span className="block text-xs text-muted-foreground">
                                    {recorded
                                      ? "Recorded."
                                      : HOP_DESCRIPTIONS[hop]}
                                  </span>
                                </span>
                              </li>
                            );
                          })}
                        </ul>
                      </div>
                    </>
                  )}
                </DataState>
              </Panel>
            </div>

            <Panel className="mt-6">
              <Tabs variant="pill" value={tab} onValueChange={setTab}>
                <TabsList>
                  <TabsTrigger value="items">
                    Items <TabCount value={items.data?.length ?? 0} />
                  </TabsTrigger>
                  <TabsTrigger value="custody">
                    Custody <TabCount value={handovers.data?.length ?? 0} />
                  </TabsTrigger>
                  <TabsTrigger value="receipts">
                    Receipts <TabCount value={receipts.data?.length ?? 0} />
                  </TabsTrigger>
                  <TabsTrigger value="returns">
                    Return leg <TabCount value={returns.data?.length ?? 0} />
                  </TabsTrigger>
                  <TabsTrigger value="cases">
                    Exception cases <TabCount value={relatedCases.length} />
                  </TabsTrigger>
                </TabsList>

                <TabsContent value="items">
                  <ManifestList
                    prefix="manifest-items"
                    query={items}
                    rows={items.data ?? []}
                    columns={itemColumns}
                    caption="The items on this manifest, with the seal and quantity expected for each and what the return leg made of it."
                    emptyTitle="No items on this manifest"
                    emptyHint="Add at least one before sealing."
                  />
                </TabsContent>

                <TabsContent value="custody">
                  <ManifestList
                    prefix="manifest-custody"
                    query={handovers}
                    rows={handovers.data ?? []}
                    columns={handoverColumns}
                    caption="Recorded custody handovers for this consignment, with the custodians, seal state, verified count and evidence for each."
                    emptyTitle="No handover recorded"
                    emptyHint="The chain of custody starts with the first handover."
                  />
                </TabsContent>

                <TabsContent value="receipts">
                  <ManifestList
                    prefix="manifest-receipts"
                    query={receipts}
                    rows={receipts.data ?? []}
                    columns={receiptColumns}
                    caption="Receipt confirmations for this consignment, with the derived outcome, counts, seal state and who received it."
                    emptyTitle="No receipt confirmed"
                    emptyHint="The destination confirms receipt when the consignment arrives."
                  />
                </TabsContent>

                <TabsContent value="returns">
                  <ManifestList
                    prefix="manifest-returns"
                    query={returns}
                    rows={returns.data ?? []}
                    columns={returnColumns}
                    caption="Return reconciliations for this consignment, with counts, shortfall, broken seals and outcome."
                    emptyTitle="No return reconciled"
                    emptyHint="The return leg is reconciled once the consignment comes back."
                  />
                </TabsContent>

                <TabsContent value="cases">
                  <ManifestList
                    prefix="manifest-cases"
                    query={exceptions}
                    rows={relatedCases}
                    columns={caseColumns}
                    onRowClick={(row) =>
                      navigate(dispatchPaths.exceptionDetail(row.id))
                    }
                    caption="Exception cases raised against this consignment, with the rule that raised each, its severity, assignee and status."
                    emptyTitle="No exception case"
                    emptyHint="Nothing has been raised against this consignment."
                  />
                </TabsContent>
              </Tabs>
            </Panel>

            {dialog === "addItem" && (
              <AddManifestItemDialog
                open
                manifest={record}
                existingItemIds={(items.data ?? []).map(
                  (row) => row.courierItemId,
                )}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess("Item added to the manifest.");
                  refreshAll();
                }}
              />
            )}
            {dialog === "seal" && (
              <SealManifestDialog
                open
                manifest={record}
                itemCount={items.data?.length ?? 0}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess(
                    "Manifest sealed. Its contents are now frozen.",
                  );
                  refreshAll();
                }}
              />
            )}
            {dialog === "assignTrip" && (
              <AssignTripDialog
                open
                manifest={record}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess("Movement assignment saved.");
                  refreshAll();
                }}
              />
            )}
            {dialog === "handover" && (
              <RecordHandoverDialog
                open
                manifest={record}
                recorded={handovers.data ?? []}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess("Handover recorded on the custody chain.");
                  refreshAll();
                }}
              />
            )}
            {dialog === "receipt" && (
              <ConfirmReceiptDialog
                open
                manifest={record}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess("Receipt confirmed.");
                  refreshAll();
                }}
              />
            )}
            {dialog === "return" && (
              <ReconcileReturnDialog
                open
                manifest={record}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess("Return leg reconciled.");
                  refreshAll();
                }}
              />
            )}
            {dialog === "close" && (
              <CloseManifestDialog
                open
                manifest={record}
                blockers={closureBlockers}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess("Manifest closed.");
                  refreshAll();
                }}
              />
            )}
          </>
        )}
      </DataState>
    </>
  );
};

const TabCount = ({ value }: { value: number }) => (
  <span className="ml-1 text-xs text-muted-foreground">{value}</span>
);

interface ManifestListProps<T> {
  prefix: string;
  query: ApiQueryState<unknown>;
  rows: T[];
  columns: TableColumn<T>[];
  caption: string;
  emptyTitle: string;
  emptyHint: string;
  onRowClick?: (row: T) => void;
}

/** One of the manifest's own collections: a failure is reported by the page's error state, not as an empty table. */
function ManifestList<T extends { id: string }>({
  prefix,
  query,
  rows,
  columns,
  caption,
  emptyTitle,
  emptyHint,
  onRowClick,
}: ManifestListProps<T>) {
  return (
    <DataState
      loading={false}
      error={query.error}
      onRetry={query.refetch}
      minHeight={200}
    >
      <Table paramPrefix={prefix} variant="soft">
        <TableContent
          variant="soft"
          columns={columns}
          data={rows}
          rowKey={(row) => row.id}
          loading={query.initialising}
          onRowClick={onRowClick ? (row) => onRowClick(row) : undefined}
          aria-label={caption}
          emptyContent={
            <EmptyState title={emptyTitle} description={emptyHint} />
          }
        />
      </Table>
    </DataState>
  );
}

export default ManifestDetailPage;
