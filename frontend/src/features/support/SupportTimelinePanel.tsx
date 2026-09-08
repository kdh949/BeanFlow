import type { components } from "../../api/schema";
import { EmptyState } from "../../design-system";
import { shortDateTime, won } from "../../lib/format";
import { StatusText } from "../../presentation/shared";

type Timeline = components["schemas"]["SupportTimelinePage"];
const typeLabels: Record<components["schemas"]["SupportTimelineType"], string> = {
  CASE_STATE: "상담 상태", CASE_ASSIGNMENT: "상담 배정", CASE_INTERACTION: "상담 내용", CASE_NOTE: "상담 메모", SUBJECT_LINK: "대상 연결",
  ORDER_STATE: "주문 상태", PAYMENT_STATE: "결제 상태", REFUND_STATE: "환불 상태", POINT_RESERVATION: "포인트 예약", COUPON_RESERVATION: "쿠폰 예약",
  PICKUP_RESERVATION: "픽업 예약", SETTLEMENT_ITEM: "정산 명세", SETTLEMENT_ADJUSTMENT: "정산 조정", NOTIFICATION_DELIVERY: "알림 전달", OPERATION_AUDIT: "업무 감사",
};

/** Case-scoped server facts; a missing load never claims an empty timeline. */
export function SupportTimelinePanel({ timeline }: { timeline: Timeline | null }) {
  return <section className="surface-card support-timeline-panel">
    <h2>관련 이력 타임라인</h2>
    {!timeline ? null : timeline.items.length === 0 ? <EmptyState title="표시할 이력이 없습니다" description="연결된 주문·결제·보상 이력이 생기면 여기에 표시됩니다." /> : <ol className="support-timeline">
      {timeline.items.map((item) => <li key={item.itemId}><span aria-hidden="true" /><div><small>{typeLabels[item.type]}</small><strong>{item.summary}</strong><p><StatusText state={item.state} /> {shortDateTime.format(new Date(item.occurredAt))}{item.amountKrw !== null ? ` · ${won.format(item.amountKrw)}` : ""}</p></div></li>)}
    </ol>}
  </section>;
}
