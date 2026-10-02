import dayjs, { type Dayjs } from 'dayjs';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@rfdtech/components';
import type { Drill } from '../api/dto';
import { drillTypeLabel } from '../api/enums';
import { DrillStatusChip } from './drillChips';

interface DrillCalendarProps {
  month: Dayjs;
  drills: Drill[];
  onMonthChange: (month: Dayjs) => void;
  onOpen: (drill: Drill) => void;
}

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/**
 * SRS-SFL-S175-01's calendar: a month of drills, Monday first. The drills are the register's own rows for the
 * month's window - the service's `from`/`to` filter - so the calendar and the list never disagree.
 */
const DrillCalendar = ({ month, drills, onMonthChange, onOpen }: DrillCalendarProps) => {
  const first = month.startOf('month');
  const lead = (first.day() + 6) % 7;
  const start = first.subtract(lead, 'day');
  const days = Array.from({ length: Math.ceil((lead + month.daysInMonth()) / 7) * 7 }, (_, i) => start.add(i, 'day'));
  const byDay = new Map<string, Drill[]>();
  drills.forEach((drill) => {
    if (!drill.plan.scheduledFor) return;
    const key = dayjs(drill.plan.scheduledFor).format('YYYY-MM-DD');
    byDay.set(key, [...(byDay.get(key) ?? []), drill]);
  });
  const today = dayjs().format('YYYY-MM-DD');

  return (
    <div>
      <div className="mb-3 flex items-center justify-between">
        <Button size="sm" variant="ghost" aria-label="Previous month" onClick={() => onMonthChange(month.subtract(1, 'month'))}><ChevronLeft size={16} aria-hidden /></Button>
        <p className="font-semibold text-gray-900">{month.format('MMMM YYYY')}</p>
        <Button size="sm" variant="ghost" aria-label="Next month" onClick={() => onMonthChange(month.add(1, 'month'))}><ChevronRight size={16} aria-hidden /></Button>
      </div>
      <div className="grid grid-cols-7 gap-px overflow-hidden rounded-md border border-[var(--clet-border-subtle)] bg-[var(--clet-border-subtle)] text-theme-xs">
        {WEEKDAYS.map((day) => <div key={day} className="bg-[var(--clet-surface-subtle)] px-2 py-1 font-medium text-gray-600">{day}</div>)}
        {days.map((day) => {
          const key = day.format('YYYY-MM-DD');
          const inMonth = day.month() === month.month();
          return (
            <div key={key} className={`min-h-24 bg-[var(--clet-surface)] p-1.5 ${inMonth ? '' : 'opacity-50'}`}>
              <p className={`mb-1 tabular-nums ${key === today ? 'font-bold text-[var(--clet-primary)]' : 'text-gray-600'}`}>{day.date()}</p>
              <div className="space-y-1">
                {(byDay.get(key) ?? []).map((drill) => (
                  <button key={drill.id} type="button" onClick={() => onOpen(drill)} className="block w-full rounded border border-[var(--clet-border-subtle)] p-1 text-left hover:bg-[var(--clet-surface-subtle)]">
                    <span className="block truncate font-medium text-gray-900">{dayjs(drill.plan.scheduledFor).format('HH:mm')} {drill.plan.title}</span>
                    <span className="block truncate text-gray-600">{drill.siteCode} · {drillTypeLabel[drill.plan.drillType]}</span>
                    <span className="mt-0.5 block"><DrillStatusChip status={drill.status} /></span>
                  </button>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default DrillCalendar;
