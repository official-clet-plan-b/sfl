import IfimpOperationsPage, { IfimpOperationsPageProps } from './IfimpOperationsPage';
import { CreateAction } from '../components/IfimpCreateDialog';

const reason = [{ key: 'reason', label: 'Reason', type: 'textarea' as const, required: true }];
const note = [{ key: 'note', label: 'Note', type: 'textarea' as const }];
const deviceActions: CreateAction[] = [
  { label: 'Update device', method: 'PATCH', path: '/api/v1/facilities/building-systems/devices/{id}', fields: [{ key: 'name', label: 'Name' }, { key: 'buildingCode', label: 'Building code' }, { key: 'expectedIntervalSeconds', label: 'Expected interval (seconds)', type: 'number' }], toBody: (v) => ({ name: v.name || null, buildingCode: v.buildingCode || null, expectedIntervalSeconds: v.expectedIntervalSeconds ? Number(v.expectedIntervalSeconds) : 0 }) },
  { label: 'Retire device', method: 'PATCH', destructive: true, path: '/api/v1/facilities/building-systems/devices/{id}/retirement', fields: reason },
];
const ruleActions: CreateAction[] = [
  { label: 'Disable rule', method: 'PATCH', path: '/api/v1/facilities/building-systems/rules/{id}/disablement', fields: [...reason, { key: 'accountableOwner', label: 'Accountable owner', required: true }] },
  { label: 'Enable rule', method: 'PATCH', path: '/api/v1/facilities/building-systems/rules/{id}/enablement', fields: reason },
];
const quarantineActions: CreateAction[] = [
  { label: 'Release reading', method: 'PATCH', path: '/api/v1/facilities/building-systems/quarantine/{id}/release', fields: note },
  { label: 'Discard reading', method: 'PATCH', destructive: true, path: '/api/v1/facilities/building-systems/quarantine/{id}/discard', fields: reason },
];
const meterActions: CreateAction[] = [
  { label: 'Update meter', method: 'PATCH', path: '/api/v1/facilities/energy/meters/{id}', fields: [{ key: 'name', label: 'Name' }, { key: 'expectedIntervalMinutes', label: 'Expected interval (minutes)', type: 'number' }, { key: 'source', label: 'Source', type: 'select', options: ['MANUAL', 'AMI', 'BMS_STREAM'] }], toBody: (v) => ({ name: v.name || null, expectedIntervalMinutes: v.expectedIntervalMinutes ? Number(v.expectedIntervalMinutes) : null, source: v.source || null }) },
  { label: 'Retire meter', method: 'PATCH', destructive: true, path: '/api/v1/facilities/energy/meters/{id}/retirement', fields: reason },
];
const readingActions: CreateAction[] = [{ label: 'Verify reading', method: 'PATCH', path: '/api/v1/facilities/energy/readings/{id}/verification', fields: [{ key: 'approve', label: 'Decision', type: 'select', required: true, options: ['true', 'false'] }, { key: 'consumption', label: 'Confirmed consumption', type: 'number' }, ...note], toBody: (v) => ({ approve: v.approve === 'true', consumption: v.consumption ? Number(v.consumption) : null, note: v.note || null }) }];
const scenarioActions: CreateAction[] = [
  { label: 'Rename scenario', method: 'PATCH', path: '/api/v1/facilities/space-planning/scenarios/{id}', fields: [{ key: 'name', label: 'Scenario name', required: true }, { key: 'description', label: 'Description', type: 'textarea' }] },
  { label: 'Set room allocation', method: 'PUT', path: '/api/v1/facilities/space-planning/scenarios/{id}/rooms/{roomId}', fields: [{ key: 'roomId', label: 'Room ID', required: true }, { key: 'allocatedUnit', label: 'Allocated unit', required: true }, { key: 'headcount', label: 'Headcount', type: 'number', required: true }], toBody: (v) => ({ units: [{ allocatedUnit: v.allocatedUnit, headcount: Number(v.headcount) }] }) },
  { label: 'Remove room allocation', method: 'DELETE', destructive: true, path: '/api/v1/facilities/space-planning/scenarios/{id}/rooms/{roomId}', fields: [{ key: 'roomId', label: 'Room ID', required: true }] },
  { label: 'Request standards override', path: '/api/v1/facilities/space-planning/scenarios/{id}/overrides', fields: [{ key: 'roomId', label: 'Room ID', required: true }, ...reason] },
  { label: 'Commit scenario', path: '/api/v1/facilities/space-planning/scenarios/{id}/commit', fields: [{ key: 'outcome', label: 'Outcome', type: 'select', required: true, options: ['SPACE_ONLY', 'REQUIRES_CONSTRUCTION'] }, ...note, { key: 'projectTitle', label: 'Construction project title' }, { key: 'projectScope', label: 'Construction scope', type: 'textarea' }] },
  { label: 'Apply scenario', path: '/api/v1/facilities/space-planning/scenarios/{id}/apply', fields: [] },
  { label: 'Create revision', path: '/api/v1/facilities/space-planning/scenarios/{id}/revisions', fields: [{ key: 'name', label: 'Revision name' }, { key: 'description', label: 'Description', type: 'textarea' }] },
  { label: 'Discard scenario', destructive: true, path: '/api/v1/facilities/space-planning/scenarios/{id}/discard', fields: reason },
];
const requestActions: CreateAction[] = [
  { label: 'Approve / reject', method: 'PATCH', path: '/api/v1/facilities/space-planning/requests/{id}/decision', fields: [{ key: 'approve', label: 'Decision', type: 'select', required: true, options: ['true', 'false'] }, ...reason], toBody: (v) => ({ approve: v.approve === 'true', reason: v.reason || null }) },
  { label: 'Link scenario', path: '/api/v1/facilities/space-planning/requests/{id}/link-scenario', fields: [{ key: 'scenarioId', label: 'Scenario ID', required: true }] },
  { label: 'Hand to construction', path: '/api/v1/facilities/space-planning/requests/{id}/hand-to-construction', fields: [{ key: 'title', label: 'Project title' }, { key: 'scope', label: 'Scope', type: 'textarea' }] },
  { label: 'Resolve request', path: '/api/v1/facilities/space-planning/requests/{id}/resolve', fields: note },
];
const cleaningTaskActions: CreateAction[] = [
  { label: 'Assign task', method: 'PATCH', path: '/api/v1/facilities/cleaning/tasks/{id}/assignment', fields: [{ key: 'assigneeType', label: 'Assignee type', type: 'select', required: true, options: ['INTERNAL', 'VENDOR'] }, { key: 'assignedTo', label: 'Assigned to', required: true }, { key: 'vendorId', label: 'Vendor ID' }] },
  { label: 'Start task', method: 'PATCH', path: '/api/v1/facilities/cleaning/tasks/{id}/start', fields: [] },
  { label: 'Complete task', method: 'PATCH', path: '/api/v1/facilities/cleaning/tasks/{id}/completion', fields: note },
  { label: 'Cancel task', method: 'PATCH', destructive: true, path: '/api/v1/facilities/cleaning/tasks/{id}/cancellation', fields: reason },
  { label: 'Submit feedback', path: '/api/v1/facilities/cleaning/tasks/{id}/feedback', fields: [{ key: 'rating', label: 'Rating (1–5)', type: 'number', required: true }, { key: 'comment', label: 'Comment', type: 'textarea' }], toBody: (v) => ({ rating: Number(v.rating), comment: v.comment || null }) },
];
const vendorActions: CreateAction[] = [
  { label: 'Change status', method: 'PATCH', path: '/api/v1/facilities/cleaning/vendors/{id}/status', fields: [{ key: 'status', label: 'Status', type: 'select', required: true, options: ['ACTIVE', 'SUSPENDED', 'INACTIVE'] }] },
  { label: 'Set SLA terms', path: '/api/v1/facilities/cleaning/vendors/{id}/sla-terms', fields: [{ key: 'responseMinutes', label: 'Response minutes', type: 'number', required: true }, { key: 'completionMinutes', label: 'Completion minutes', type: 'number', required: true }, { key: 'qualityFloor', label: 'Quality floor', type: 'number', required: true }], toBody: (v) => ({ responseMinutes: Number(v.responseMinutes), completionMinutes: Number(v.completionMinutes), qualityFloor: Number(v.qualityFloor) }) },
];
const setupActions: CreateAction[] = [
  { label: 'Add resource requests', path: '/api/v1/facilities/event-logistics/setup-tasks/{id}/resource-requests', fields: [{ key: 'resourceType', label: 'Resource type', type: 'select', required: true, options: ['ROOM', 'CATERING', 'SECURITY', 'CLEANING', 'EQUIPMENT', 'TRANSPORT', 'OTHER'] }, { key: 'description', label: 'Description', type: 'textarea', required: true }, { key: 'quantity', label: 'Quantity', type: 'number', required: true }], toBody: (v) => ({ applyTemplate: false, requests: [{ resourceType: v.resourceType, description: v.description, quantity: Number(v.quantity) }] }) },
  { label: 'Link risk assessment', method: 'PUT', path: '/api/v1/facilities/event-logistics/setup-tasks/{id}/risk-assessment', fields: [{ key: 'assessmentId', label: 'Assessment ID', required: true }, { key: 'version', label: 'Assessment version', type: 'number' }], toBody: (v) => ({ assessmentId: v.assessmentId, version: v.version ? Number(v.version) : null }) },
  { label: 'Confirm readiness', method: 'PATCH', path: '/api/v1/facilities/event-logistics/setup-tasks/{id}/confirm', fields: [] },
  { label: 'Complete setup', method: 'PATCH', path: '/api/v1/facilities/event-logistics/setup-tasks/{id}/complete', fields: note },
  { label: 'Route resource request', method: 'PATCH', path: '/api/v1/facilities/event-logistics/resource-requests/{requestId}/route', fields: [{ key: 'requestId', label: 'Resource request ID', required: true }, { key: 'routeTo', label: 'Owning system', required: true }, ...note] },
  { label: 'Accept manual coordination', method: 'PATCH', path: '/api/v1/facilities/event-logistics/resource-requests/{requestId}/manual-coordination/accept', fields: [{ key: 'requestId', label: 'Resource request ID', required: true }, ...note] },
  { label: 'Cancel resource request', method: 'PATCH', destructive: true, path: '/api/v1/facilities/event-logistics/resource-requests/{requestId}/cancel', fields: [{ key: 'requestId', label: 'Resource request ID', required: true }, ...reason] },
  { label: 'Reconcile event', path: '/api/v1/facilities/event-logistics/setup-tasks/{id}/reconciliation', fields: [{ key: 'actualAttendance', label: 'Actual attendance', type: 'number', required: true }, { key: 'resourceGaps', label: 'Resource gaps / lessons', type: 'textarea' }], toBody: (v) => ({ actualAttendance: Number(v.actualAttendance), resourceGaps: v.resourceGaps ? [v.resourceGaps] : [] }) },
];
const projectActions: CreateAction[] = [
  { label: 'Approve project', method: 'PATCH', path: '/api/v1/facilities/construction/projects/{id}/approval', fields: note },
  { label: 'Start project', method: 'PATCH', path: '/api/v1/facilities/construction/projects/{id}/start', fields: [] },
  { label: 'Revise baseline', method: 'PATCH', path: '/api/v1/facilities/construction/projects/{id}/baseline', fields: [{ key: 'amount', label: 'New baseline', type: 'number', required: true }, { key: 'currency', label: 'Currency', initial: 'GHS' }, ...reason], toBody: (v) => ({ amount: Number(v.amount), currency: v.currency || null, reason: v.reason }) },
  { label: 'Submit variation', path: '/api/v1/facilities/construction/projects/{id}/variations', fields: [{ key: 'changeDescription', label: 'Change description', type: 'textarea', required: true }, { key: 'costDelta', label: 'Cost change', type: 'number', required: true }, { key: 'currency', label: 'Currency', initial: 'GHS' }, { key: 'justification', label: 'Justification', type: 'textarea', required: true }], toBody: (v) => ({ ...v, costDelta: Number(v.costDelta) }) },
  { label: 'Decide variation', method: 'PATCH', path: '/api/v1/facilities/construction/projects/{id}/variations/{variationId}/decision', fields: [{ key: 'variationId', label: 'Variation ID', required: true }, { key: 'approve', label: 'Decision', type: 'select', required: true, options: ['true', 'false'] }, ...note], toBody: (v) => ({ approve: v.approve === 'true', note: v.note || null }) },
  { label: 'Add milestone', path: '/api/v1/facilities/construction/projects/{id}/milestones', fields: [{ key: 'code', label: 'Milestone code', required: true }, { key: 'name', label: 'Milestone name', required: true }, { key: 'targetDate', label: 'Target date', type: 'date', required: true }] },
  { label: 'Achieve milestone', method: 'PATCH', path: '/api/v1/facilities/construction/projects/{id}/milestones/{milestoneId}/achievement', fields: [{ key: 'milestoneId', label: 'Milestone ID', required: true }, { key: 'achievedOn', label: 'Achieved on', type: 'date' }] },
  { label: 'Assign contractor', path: '/api/v1/facilities/construction/projects/{id}/contractors', fields: [{ key: 'contractorId', label: 'Contractor ID', required: true }, { key: 'role', label: 'Role', required: true }] },
  { label: 'Link permit', path: '/api/v1/facilities/construction/projects/{id}/permits', fields: [{ key: 'permitId', label: 'Permit reference', required: true }, { key: 'workType', label: 'Work type', required: true }] },
  { label: 'Record handover', path: '/api/v1/facilities/construction/projects/{id}/handover', fields: [{ key: 'handoverDate', label: 'Handover date', type: 'date' }, { key: 'notes', label: 'Notes', type: 'textarea' }], toBody: (v) => ({ handoverDate: v.handoverDate || null, notes: v.notes || null, roomChanges: [] }) },
  { label: 'Practical completion', method: 'PATCH', path: '/api/v1/facilities/construction/projects/{id}/practical-completion', fields: [] },
  { label: 'Raise defect', path: '/api/v1/facilities/construction/projects/{id}/defects', fields: [{ key: 'contractorId', label: 'Contractor ID', required: true }, { key: 'description', label: 'Defect description', type: 'textarea', required: true }, { key: 'roomId', label: 'Room ID' }, { key: 'locationCode', label: 'Location code' }, { key: 'priority', label: 'Priority', type: 'select', required: true, options: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] }] },
  { label: 'Defer defect', method: 'PATCH', path: '/api/v1/facilities/construction/projects/{id}/defects/{defectId}/deferral', fields: [{ key: 'defectId', label: 'Defect ID', required: true }, ...reason] },
  { label: 'Close project', method: 'PATCH', path: '/api/v1/facilities/construction/projects/{id}/closure', fields: [] },
  { label: 'Cancel project', method: 'PATCH', destructive: true, path: '/api/v1/facilities/construction/projects/{id}/cancellation', fields: reason },
];
const contractorActions: CreateAction[] = [
  { label: 'Update insurance', method: 'PATCH', path: '/api/v1/facilities/construction/contractors/{id}/insurance', fields: [{ key: 'insuranceProvider', label: 'Insurance provider' }, { key: 'insurancePolicyReference', label: 'Policy reference' }, { key: 'insuranceExpiresOn', label: 'Insurance expiry', type: 'date', required: true }] },
  { label: 'Record competency', path: '/api/v1/facilities/construction/contractors/{id}/competencies', fields: [{ key: 'certificationCode', label: 'Certification code', required: true }, { key: 'description', label: 'Description' }, { key: 'certificateReference', label: 'Certificate reference' }, { key: 'expiresOn', label: 'Expiry date', type: 'date', required: true }] },
  { label: 'Request site access', path: '/api/v1/facilities/construction/contractors/{id}/site-access', fields: [{ key: 'projectId', label: 'Project ID' }, { key: 'accessScope', label: 'Access scope', required: true }, { key: 'validFrom', label: 'Valid from', type: 'datetime' }, { key: 'validTo', label: 'Valid to', type: 'datetime', required: true }] },
];

const page = (props: IfimpOperationsPageProps) => () => <IfimpOperationsPage {...props} />;

export const BuildingSystemsPage = page({
  system: 'S156',
  title: 'Building systems & IoT',
  subtitle: 'Device health, telemetry, alerting rules and quarantined readings',
  dependencyNote: 'The BACnet/MQTT bridge is simulated. Critical events are recorded for SSEMP, but no SSEMP consumer is currently connected.',
  views: [
    { label: 'Health', path: '/api/v1/facilities/building-systems/health', description: 'Site-to-device operating health and active alert position.' },
    { label: 'Devices', path: '/api/v1/facilities/building-systems/devices', description: 'Registered BMS devices and their lifecycle state.', actions: deviceActions, create: { label: 'Register device', path: '/api/v1/facilities/building-systems/devices', fields: [
      { key: 'siteCode', label: 'Site code', required: true }, { key: 'deviceCode', label: 'Device code', required: true }, { key: 'avampAssetId', label: 'AVAMP asset ID', required: true }, { key: 'name', label: 'Name', required: true },
      { key: 'systemType', label: 'System type', type: 'select', required: true, options: ['HVAC', 'ELECTRICAL', 'WATER', 'FIRE', 'LIFT', 'GENERATOR', 'OTHER'] }, { key: 'kind', label: 'Device kind', type: 'select', required: true, options: ['SENSOR', 'ACTUATOR', 'CONTROLLER', 'GATEWAY', 'METER'] }, { key: 'buildingCode', label: 'Building code', required: true }, { key: 'expectedIntervalSeconds', label: 'Expected interval (seconds)', type: 'number', required: true, initial: '300' },
    ], toBody: (v) => ({ ...v, expectedIntervalSeconds: Number(v.expectedIntervalSeconds) }) } },
    { label: 'Alerts', path: '/api/v1/facilities/building-systems/alerts', description: 'Active and cleared threshold alerts.' },
    { label: 'Quarantine', path: '/api/v1/facilities/building-systems/quarantine', description: 'Telemetry awaiting a resolvable device or location.', actions: quarantineActions },
    { label: 'Rules', path: '/api/v1/facilities/building-systems/rules', description: 'Current threshold and fault-code rules.', actions: ruleActions, create: { label: 'Create rule', path: '/api/v1/facilities/building-systems/rules', fields: [
      { key: 'siteCode', label: 'Site code', required: true }, { key: 'name', label: 'Rule name', required: true }, { key: 'quantity', label: 'Measured quantity', type: 'select', required: true, options: ['TEMPERATURE_C', 'HUMIDITY_PERCENT', 'PRESSURE_KPA', 'POWER_KW', 'ELECTRICAL_ENERGY_KWH', 'WATER_VOLUME_M3'] }, { key: 'condition', label: 'Condition', type: 'select', required: true, options: ['ABOVE', 'BELOW', 'OUTSIDE_RANGE', 'FAULT_CODE'] }, { key: 'upperLimit', label: 'Upper limit', type: 'number' }, { key: 'priority', label: 'Priority', type: 'select', required: true, options: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] }, { key: 'reason', label: 'Reason', type: 'textarea', required: true },
    ], toBody: (v) => ({ ...v, upperLimit: v.upperLimit ? Number(v.upperLimit) : null }) } },
    { label: 'Readings', path: '/api/v1/facilities/building-systems/readings', description: 'Latest accepted device readings.' },
  ],
});

export const EnergyPage = page({
  system: 'S157',
  title: 'Energy & sustainability',
  subtitle: 'Meters, readings, consumption, budgets and sustainability reporting',
  dependencyNote: 'Metering uses the simulated vendor adapter until procurement selects a provider; KPI publication currently has no S149/S225 consumer.',
  views: [
    { label: 'Health', path: '/api/v1/facilities/energy/health', description: 'Metering integration health, source coverage and held readings.' },
    { label: 'Meters', path: '/api/v1/facilities/energy/meters', description: 'Utility meters and acquisition sources.', actions: meterActions, create: { label: 'Register meter', path: '/api/v1/facilities/energy/meters', fields: [
      { key: 'siteCode', label: 'Site code', required: true }, { key: 'buildingCode', label: 'Building code', required: true }, { key: 'meterCode', label: 'Meter code', required: true }, { key: 'name', label: 'Name', required: true }, { key: 'utility', label: 'Utility', type: 'select', required: true, options: ['ELECTRICITY', 'WATER', 'GENERATOR_FUEL'] }, { key: 'source', label: 'Source', type: 'select', required: true, options: ['MANUAL', 'AMI', 'BMS_STREAM'] }, { key: 'expectedIntervalMinutes', label: 'Expected interval (minutes)', type: 'number', initial: '1440' },
    ], toBody: (v) => ({ ...v, expectedIntervalMinutes: v.expectedIntervalMinutes ? Number(v.expectedIntervalMinutes) : null }) } },
    { label: 'Readings', path: '/api/v1/facilities/energy/readings', description: 'Posted and held consumption readings.', actions: readingActions, create: { label: 'Record manual reading', path: '/api/v1/facilities/energy/readings/manual', fields: [{ key: 'meterId', label: 'Meter ID', required: true }, { key: 'registerValue', label: 'Register value', type: 'number', required: true }, { key: 'note', label: 'Note', type: 'textarea' }], toBody: (v) => ({ ...v, registerValue: Number(v.registerValue) }) } },
    { label: 'Consumption', path: '/api/v1/facilities/energy/consumption', description: 'Calculated utility consumption by meter and period.' },
    { label: 'Periods', path: '/api/v1/facilities/energy/periods', description: 'Open and closed reporting periods.', create: { label: 'Close period', path: '/api/v1/facilities/energy/periods/close', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'utility', label: 'Utility', type: 'select', required: true, options: ['ELECTRICITY', 'WATER', 'GENERATOR_FUEL'] }, { key: 'periodStart', label: 'Period start', type: 'date', required: true }] } },
    { label: 'Variance', path: '/api/v1/facilities/energy/variance', query: { utility: 'ELECTRICITY' }, description: 'Actual consumption and cost compared with budget.' },
    { label: 'Cost', path: '/api/v1/facilities/energy/cost', query: { utility: 'ELECTRICITY' }, description: 'Tariff-rated energy cost.' },
    { label: 'Alerts', path: '/api/v1/facilities/energy/alerts', description: 'Variance, anomaly and missing-tariff exceptions.' },
    { label: 'Budgets', path: '/api/v1/facilities/energy/budgets', description: 'Effective-dated utility budgets.', create: { label: 'Create budget', path: '/api/v1/facilities/energy/budgets', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'utility', label: 'Utility', type: 'select', required: true, options: ['ELECTRICITY', 'WATER', 'GENERATOR_FUEL'] }, { key: 'periodStart', label: 'Period start', type: 'date', required: true }, { key: 'consumptionBudget', label: 'Consumption budget', type: 'number', required: true }, { key: 'costBudget', label: 'Cost budget', type: 'number' }, { key: 'currency', label: 'Currency', initial: 'GHS' }], toBody: (v) => ({ ...v, consumptionBudget: Number(v.consumptionBudget), costBudget: v.costBudget ? Number(v.costBudget) : null }) } },
    { label: 'Tariffs', path: '/api/v1/facilities/energy/tariffs', description: 'Tariff versions used to calculate cost.', create: { label: 'Create tariff', path: '/api/v1/facilities/energy/tariffs', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'utility', label: 'Utility', type: 'select', required: true, options: ['ELECTRICITY', 'WATER', 'GENERATOR_FUEL'] }, { key: 'unitRate', label: 'Unit rate', type: 'number', required: true }, { key: 'currency', label: 'Currency', required: true, initial: 'GHS' }, { key: 'validFrom', label: 'Valid from', type: 'date', required: true }, { key: 'reason', label: 'Reason', type: 'textarea' }], toBody: (v) => ({ ...v, unitRate: Number(v.unitRate) }) } },
    { label: 'KPIs', path: '/api/v1/facilities/energy/kpis', description: 'Sustainability indicators with completeness status.', create: { label: 'Compute KPIs', path: '/api/v1/facilities/energy/kpis/computation', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'periodStart', label: 'Period start', type: 'date', required: true }, { key: 'periodEnd', label: 'Period end', type: 'date', required: true }] } },
    { label: 'Emission factors', path: '/api/v1/facilities/energy/emission-factors', description: 'Versioned factors used for carbon calculations.', create: { label: 'Add emission factor', path: '/api/v1/facilities/energy/emission-factors', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'utility', label: 'Utility', type: 'select', required: true, options: ['ELECTRICITY', 'WATER', 'GENERATOR_FUEL'] }, { key: 'kgCo2ePerUnit', label: 'kg CO2e per unit', type: 'number', required: true }, { key: 'validFrom', label: 'Valid from', type: 'date', required: true }, { key: 'sourceReference', label: 'Source reference', required: true }], toBody: (v) => ({ ...v, kgCo2ePerUnit: Number(v.kgCo2ePerUnit) }) } },
  ],
});

export const SpacePlanningPage = page({
  system: 'S158',
  title: 'Space planning & moves',
  subtitle: 'Scenarios, allocations, occupancy compliance and change requests',
  dependencyNote: 'Organisation units are locally recorded until HRMS S140 is available; CAD floorplans are not part of the current service.',
  views: [
    { label: 'Dashboard', path: '/api/v1/facilities/space-planning/dashboard', description: 'Planning pipeline, compliance and utilisation exceptions.' },
    { label: 'Scenarios', path: '/api/v1/facilities/space-planning/scenarios', description: 'Draft, committed and applied allocation scenarios.', actions: scenarioActions, create: { label: 'Create scenario', path: '/api/v1/facilities/space-planning/scenarios', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'name', label: 'Scenario name', required: true }, { key: 'description', label: 'Description', type: 'textarea' }] } },
    { label: 'Allocations', path: '/api/v1/facilities/space-planning/allocations', description: 'Current S152 room allocation register.' },
    { label: 'Standards', path: '/api/v1/facilities/space-planning/standards', description: 'Occupancy standards and their versions.', create: { label: 'Define standard', path: '/api/v1/facilities/space-planning/standards', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'spaceType', label: 'Space type', type: 'select', required: true, options: ['OFFICE', 'MEETING_ROOM', 'LECTURE_HALL', 'EXAMINATION_HALL', 'LABORATORY', 'LIBRARY', 'STORE', 'PLANT_ROOM'] }, { key: 'maxCapacityPercent', label: 'Maximum capacity (%)', type: 'number' }, { key: 'minAreaPerPersonSqm', label: 'Minimum area/person (m²)', type: 'number' }, { key: 'note', label: 'Note', type: 'textarea' }], toBody: (v) => ({ ...v, maxCapacityPercent: v.maxCapacityPercent ? Number(v.maxCapacityPercent) : null, minAreaPerPersonSqm: v.minAreaPerPersonSqm ? Number(v.minAreaPerPersonSqm) : null }) } },
    { label: 'Utilisation', path: '/api/v1/facilities/space-planning/utilisation/signals', description: 'Active under-use and over-use planning signals.' },
    { label: 'Change requests', path: '/api/v1/facilities/space-planning/requests', description: 'Requested moves and physical works through decision.', actions: requestActions, create: { label: 'Submit change request', path: '/api/v1/facilities/space-planning/requests', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'requestingUnit', label: 'Requesting unit', required: true }, { key: 'justification', label: 'Justification', type: 'textarea', required: true }, { key: 'targetAreaDescription', label: 'Target area' }, { key: 'requiredHeadcount', label: 'Required headcount', type: 'number' }, { key: 'urgency', label: 'Urgency', type: 'select', options: ['LOW', 'NORMAL', 'HIGH', 'URGENT'], initial: 'NORMAL' }], toBody: (v) => ({ ...v, requiredHeadcount: v.requiredHeadcount ? Number(v.requiredHeadcount) : null }) } },
  ],
});

export const CleaningPage = page({
  system: 'S169',
  title: 'Cleaning operations',
  subtitle: 'Schedules, service tasks, checklists, feedback and vendor performance',
  dependencyNote: 'The vendor register is local until S133 is available. Evidence records hold object references and hashes; this UI does not imply that binary storage is connected.',
  views: [
    { label: 'Dashboard', path: '/api/v1/facilities/cleaning/dashboard', description: 'Scheduled/completed work, overdue requests, SLA performance and feedback.' },
    { label: 'Tasks', path: '/api/v1/facilities/cleaning/tasks', description: 'Routine, booking-triggered and reactive cleaning work.', actions: cleaningTaskActions, create: { label: 'Raise cleaning task', path: '/api/v1/facilities/cleaning/tasks', fields: [{ key: 'roomId', label: 'Room ID', required: true }, { key: 'origin', label: 'Origin', type: 'select', required: true, options: ['ADHOC', 'BOOKING_SETUP', 'BOOKING_TEARDOWN'] }, { key: 'title', label: 'Title', required: true }, { key: 'description', label: 'Description', type: 'textarea' }, { key: 'windowStart', label: 'Window start', type: 'datetime' }, { key: 'dueBy', label: 'Due by', type: 'datetime', required: true }] } },
    { label: 'Schedules', path: '/api/v1/facilities/cleaning/schedules', description: 'Recurring cleaning schedules.', create: { label: 'Create schedule', path: '/api/v1/facilities/cleaning/schedules', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'name', label: 'Schedule name', required: true }, { key: 'spaceType', label: 'Space type', type: 'select', required: true, options: ['OFFICE', 'MEETING_ROOM', 'LECTURE_HALL', 'EXAMINATION_HALL', 'LABORATORY', 'LIBRARY', 'STORE', 'PLANT_ROOM'] }, { key: 'roomId', label: 'Room ID' }, { key: 'frequency', label: 'Frequency', type: 'select', required: true, options: ['DAILY', 'WEEKLY', 'MONTHLY'] }, { key: 'timesOfDay', label: 'Time of day (HH:mm)', required: true, initial: '07:00' }, { key: 'durationMinutes', label: 'Duration (minutes)', type: 'number', required: true, initial: '60' }], toBody: (v) => ({ ...v, roomId: v.roomId || null, timesOfDay: [v.timesOfDay], daysOfWeek: v.frequency === 'WEEKLY' ? ['MONDAY'] : [], durationMinutes: Number(v.durationMinutes) }) } },
    { label: 'Checklists', path: '/api/v1/facilities/cleaning/checklist-templates', description: 'Checklist templates used as completion evidence.', create: { label: 'Create checklist', path: '/api/v1/facilities/cleaning/checklist-templates', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'spaceType', label: 'Space type', type: 'select', required: true, options: ['OFFICE', 'MEETING_ROOM', 'LECTURE_HALL', 'EXAMINATION_HALL', 'LABORATORY', 'LIBRARY', 'STORE', 'PLANT_ROOM'] }, { key: 'name', label: 'Template name', required: true }, { key: 'itemCode', label: 'First item code', required: true }, { key: 'itemLabel', label: 'First checklist item', required: true }], toBody: (v) => ({ siteCode: v.siteCode, spaceType: v.spaceType, name: v.name, items: [{ itemCode: v.itemCode, label: v.itemLabel, photoRequired: false }] }) } },
    { label: 'Vendors', path: '/api/v1/facilities/cleaning/vendors', description: 'Cleaning suppliers and current service terms.', actions: vendorActions, create: { label: 'Register vendor', path: '/api/v1/facilities/cleaning/vendors', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'vendorMasterReference', label: 'Vendor master reference', required: true }] } },
    { label: 'Low ratings', path: '/api/v1/facilities/cleaning/low-rating-flags', description: 'Feedback exceptions awaiting supervisor review.' },
    { label: 'Capacity', path: '/api/v1/facilities/cleaning/capacity', description: 'Crew capacity and competing commitments.' },
  ],
});

export const EventLogisticsPage = page({
  system: 'S173',
  title: 'Event logistics',
  subtitle: 'Set-up readiness, resource coordination and post-event reconciliation',
  dependencyNote: 'Catering is represented as manual coordination until S172 exists. Higher-risk confirmation remains fail-closed until S165 publishes a current assessment.',
  views: [
    { label: 'Set-up tasks', path: '/api/v1/facilities/event-logistics/setup-tasks', description: 'Upcoming events ordered by readiness risk.', actions: setupActions },
    { label: 'Templates', path: '/api/v1/facilities/event-logistics/templates', description: 'Resource lessons learned from reconciliation gaps.' },
    { label: 'Risk criteria', path: '/api/v1/facilities/event-logistics/risk-criteria', description: 'Criteria that make an event higher risk.', create: { label: 'Configure criteria', path: '/api/v1/facilities/event-logistics/risk-criteria', method: 'PUT', fields: [{ key: 'attendanceThreshold', label: 'Attendance threshold', type: 'number', required: true }, { key: 'higherRiskCategories', label: 'Higher-risk category' }], toBody: (v) => ({ attendanceThreshold: Number(v.attendanceThreshold), higherRiskCategories: v.higherRiskCategories ? [v.higherRiskCategories] : [], externalContractorsAreHigherRisk: true, temporaryStructuresAreHigherRisk: true }) } },
    { label: 'Integration status', path: '/api/v1/facilities/event-logistics/integration', description: 'Availability of each owning system used by event logistics.' },
  ],
});

export const ConstructionPage = page({
  system: 'S176',
  title: 'Construction projects',
  subtitle: 'Capital works from registration and approval through handover and closure',
  dependencyNote: 'Permit-required work remains fail-closed until S164 Permit-to-Work is available. Access suspension is published but has no S160a consumer.',
  views: [
    { label: 'Dashboard', path: '/api/v1/facilities/construction/dashboard', description: 'Project pipeline, milestones, variations and contractor exceptions.' },
    { label: 'Projects', path: '/api/v1/facilities/construction/projects', description: 'Construction projects at every lifecycle stage.', actions: projectActions, create: { label: 'Register project', path: '/api/v1/facilities/construction/projects', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'title', label: 'Project title', required: true }, { key: 'scope', label: 'Scope', type: 'textarea', required: true }, { key: 'workTypes', label: 'Work type', required: true, initial: 'REFURBISHMENT' }, { key: 'budgetBaseline', label: 'Budget baseline', type: 'number', required: true }, { key: 'currency', label: 'Currency', required: true, initial: 'GHS' }, { key: 'fundingSourceReference', label: 'Funding source reference', required: true }, { key: 'milestoneName', label: 'First milestone', required: true, initial: 'Practical completion' }, { key: 'milestoneDate', label: 'Milestone target', type: 'date', required: true }], toBody: (v) => ({ siteCode: v.siteCode, title: v.title, scope: v.scope, workTypes: [v.workTypes], budgetBaseline: Number(v.budgetBaseline), currency: v.currency, fundingSourceReference: v.fundingSourceReference, milestones: [{ code: 'M1', name: v.milestoneName, targetDate: v.milestoneDate }], contractors: [] }) } },
    { label: 'Contractors', path: '/api/v1/facilities/construction/contractors', description: 'Contractor insurance, competency and access standing.', actions: contractorActions, create: { label: 'Register contractor', path: '/api/v1/facilities/construction/contractors', fields: [{ key: 'siteCode', label: 'Site code', required: true }, { key: 'contractorCode', label: 'Contractor code', required: true }, { key: 'name', label: 'Name', required: true }, { key: 'vendorReference', label: 'Vendor reference' }, { key: 'insuranceProvider', label: 'Insurance provider' }, { key: 'insurancePolicyReference', label: 'Policy reference' }, { key: 'insuranceExpiresOn', label: 'Insurance expiry', type: 'date', required: true }] } },
    { label: 'Integration status', path: '/api/v1/facilities/construction/dashboard/integrations', description: 'Evidence-backed status of S176 dependencies.' },
  ],
});
