import { Check } from "lucide-react";
import type { ReactNode } from "react";

export type WorkspaceDataColumn = {
  key: string;
  label: string;
  align?: "start" | "center" | "end";
  width?: string;
};

export type WorkspaceDataRow = {
  id: string;
  cells: Record<string, ReactNode>;
  selected?: boolean;
};

export type WorkspaceDataTableProps = {
  caption: string;
  columns: WorkspaceDataColumn[];
  rows: WorkspaceDataRow[];
  empty?: ReactNode;
};

export function WorkspaceDataTable({ caption, columns, rows, empty = "표시할 항목이 없습니다." }: WorkspaceDataTableProps) {
  return (
    <div className="bf-workspace-table-scroll">
      <table className="bf-workspace-table">
        <caption>{caption}</caption>
        <colgroup>{columns.map((column) => <col key={column.key} style={column.width ? { width: column.width } : undefined} />)}</colgroup>
        <thead><tr>{columns.map((column) => <th key={column.key} scope="col" data-align={column.align ?? "start"}>{column.label}</th>)}</tr></thead>
        <tbody>
          {rows.length ? rows.map((row) => (
            <tr key={row.id} aria-selected={row.selected || undefined}>
              {columns.map((column) => <td key={column.key} data-align={column.align ?? "start"}>{row.cells[column.key]}</td>)}
            </tr>
          )) : <tr><td className="bf-workspace-table-empty" colSpan={columns.length}>{empty}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

export function FilterToolbar({ label = "목록 필터", children, actions }: { label?: string; children: ReactNode; actions?: ReactNode }) {
  return <section className="bf-filter-toolbar" aria-label={label}><div className="bf-filter-toolbar__fields">{children}</div>{actions ? <div className="bf-filter-toolbar__actions">{actions}</div> : null}</section>;
}

export type MetricStripItem = { id: string; label: string; value: ReactNode; hint?: ReactNode; icon?: ReactNode; tone?: "neutral" | "accent" | "warning" };

export function MetricStrip({ items, label = "주요 지표" }: { items: MetricStripItem[]; label?: string }) {
  return <dl className="bf-metric-strip" aria-label={label}>{items.map((item) => <div key={item.id} data-tone={item.tone ?? "neutral"}><dt>{item.label}{item.icon ? <span className="bf-metric-strip__icon" aria-hidden="true">{item.icon}</span> : null}</dt><dd>{item.value}{item.hint ? <small>{item.hint}</small> : null}</dd></div>)}</dl>;
}

export type EventTimelineItem = { id: string; title: ReactNode; occurredAt: ReactNode; description?: ReactNode; meta?: ReactNode; tone?: "neutral" | "accent" | "success" | "warning" | "danger" };

export function EventTimeline({ items, label = "처리 이력" }: { items: EventTimelineItem[]; label?: string }) {
  return <ol className="bf-event-timeline" aria-label={label}>{items.map((item) => <li key={item.id} data-tone={item.tone ?? "neutral"}><span className="bf-event-timeline__dot" aria-hidden="true" /><div className="bf-event-timeline__time">{item.occurredAt}</div><div className="bf-event-timeline__body"><strong>{item.title}</strong>{item.description ? <p>{item.description}</p> : null}{item.meta ? <small>{item.meta}</small> : null}</div></li>)}</ol>;
}

export type WorkflowStep = { id: string; label: string; description?: string; state: "complete" | "current" | "upcoming" | "failed" };

export function WorkflowStepper({ steps, label = "처리 단계" }: { steps: WorkflowStep[]; label?: string }) {
  return <ol className="bf-workflow-stepper" aria-label={label}>{steps.map((step, index) => <li key={step.id} data-state={step.state} aria-current={step.state === "current" ? "step" : undefined}><span className="bf-workflow-stepper__mark" aria-hidden="true">{step.state === "complete" ? <Check size={15} /> : index + 1}</span><div><strong>{step.label}</strong>{step.description ? <small>{step.description}</small> : null}</div></li>)}</ol>;
}
