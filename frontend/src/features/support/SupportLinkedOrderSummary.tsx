import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { unwrap } from "../../api/client";
import { Button, ButtonLink, EmptyState, LoadingState, SelectField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime, won } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { supportSubjectLabel } from "./supportCaseLabels";
/** Chooses an active Case link; the server rechecks assignment and order read permission. */
export function SupportLinkedOrderSummary({ caseId, links }: { caseId: string; links: components["schemas"]["SupportSubjectLink"][] }) {
  const orders = links.filter(link => link.subjectType === "ORDER");
  const [selection, setSelection] = useState("");
  const [opened, setOpened] = useState("");
  if (!orders.length) return <EmptyState title="연결된 주문이 없습니다" description="대상 연결에서 공개 주문번호로 주문을 찾아 연결할 수 있습니다." />;
  return <section className="management-workspace" aria-label="연결 주문 요약"><SelectField label="연결된 주문 선택" value={selection} onValueChange={value => { setSelection(value); setOpened(""); }}><option value="">주문 선택</option>{orders.map(link => <option key={link.linkId} value={link.subjectId}>{supportSubjectLabel(link)}</option>)}</SelectField><Button disabled={!orders.some(link => link.subjectId === selection)} onClick={() => setOpened(selection)}>주문 요약 조회</Button>{opened && orders.some(link => link.subjectId === opened) ? <OrderSummary key={`${caseId}:${opened}`} caseId={caseId} orderId={opened} /> : null}</section>;
}
function OrderSummary({ caseId, orderId }: { caseId: string; orderId: string }) {
  const { state, reload } = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/orders/{orderId}/overview", { params: { path: { orderId }, query: { caseId } } })), [caseId, orderId]));
  if (state.status === "loading") return <LoadingState label="주문 당시 품목과 금액을 확인하는 중" />;
  if (state.status === "failed") return <ErrorState error={state.error} retry={reload} />;
  const order = state.value;
  return <article className="surface-card management-card management-workspace"><h2>{order.publicReference} · {order.storeName}</h2><StatusText state={order.state} /><dl className="detail-list"><div><dt>주문 시각</dt><dd>{fullDateTime.format(new Date(order.orderedAt))}</dd></div><div><dt>픽업 시간</dt><dd>{fullDateTime.format(new Date(order.pickupWindowStart))} ~ {fullDateTime.format(new Date(order.pickupWindowEnd))}</dd></div></dl><ul>{order.lines.map(line => <li key={line.sequence}><strong>{line.menuName} · {line.quantity}개</strong><p>{won.format(line.amountKrw)}</p></li>)}</ul><dl className="detail-list"><div><dt>상품 합계</dt><dd>{won.format(order.subtotalKrw)}</dd></div><div><dt>쿠폰 할인</dt><dd>{won.format(order.couponDiscountKrw)}</dd></div><div><dt>사용 포인트</dt><dd>{won.format(order.pointsAppliedKrw)}</dd></div><div><dt>주문 당시 결제 대상 금액</dt><dd>{won.format(order.payableKrw)}</dd></div></dl><p>현재 결제·환불 진행 상태는 상담 이력에서 확인할 수 있습니다.</p><div className="button-row"><Button variant="secondary" onClick={reload}>주문 요약 새로고침</Button><ButtonLink variant="secondary" to={`/support/follow-up?caseId=${encodeURIComponent(caseId)}`}>결제·환불 이력과 후속 업무</ButtonLink></div></article>;
}
