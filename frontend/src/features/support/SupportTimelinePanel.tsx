import type { components } from "../../api/schema";
import { EmptyState } from "../../design-system";
import { shortDateTime, won } from "../../lib/format";
import { StatusText } from "../../presentation/shared";
import { supportTimelinePresentation } from "./supportTimelinePresentation";

type Timeline = components["schemas"]["SupportTimelinePage"];

/** Case-scoped server facts; a missing load never claims an empty timeline. */
export function SupportTimelinePanel({ timeline }: { timeline: Timeline | null }) {
  return <section className="surface-card support-timeline-panel">
    <h2>관련 이력 타임라인</h2>
    {!timeline ? null : timeline.items.length === 0 ? <EmptyState title="표시할 이력이 없습니다" description="연결된 주문·결제·보상 이력이 생기면 여기에 표시됩니다." /> : <ol className="support-timeline">
      {timeline.items.map((item) => {
        const presentation = supportTimelinePresentation(item);
        return <li key={item.itemId}><span aria-hidden="true" /><div><small>{presentation.typeLabel}</small><strong>{presentation.summary}</strong><p><StatusText state={item.state} label={presentation.stateLabel} /> {shortDateTime.format(new Date(item.occurredAt))}{item.amountKrw !== null ? ` · ${won.format(item.amountKrw)}` : ""}</p></div></li>;
      })}
    </ol>}
  </section>;
}
