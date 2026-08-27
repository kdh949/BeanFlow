import {
  Check,
  ChevronRight,
  Clock3,
  CreditCard,
  RefreshCw,
  RotateCcw,
  ShieldCheck,
  Timer,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, idempotencyKey, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { couponSelection, useCouponSelection } from "../../features/customer/couponSelection";
import { useResource } from "../../features/shared/useResource";
import { shortDateTime, shortTime, won } from "../../lib/format";
import { requestTossStandardPayment } from "../../payment/toss";
import { attemptStorage } from "../../features/payment/paymentAttempt";
import { pickupNumberNote } from "../../features/ordering/orderPresentation";
import { reorderFailure } from "../../features/ordering/reorderFailures";
import { RefreshEmpty, RefreshError, RefreshLoading, RefreshMobileTopbar } from "./RefreshShared";
import { Button } from "../../design-system";
import { StatusText } from "../shared";

type Order = components["schemas"]["Order"];
type ReorderPriceComparison = components["schemas"]["ReorderPriceComparison"];
type CustomerOrderDetail = components["schemas"]["CustomerOrderDetail"];
type CancellationReasonCode = components["schemas"]["CancellationReasonCode"];
type PickupSlot = components["schemas"]["PickupSlot"];

export function RefreshCheckoutPage() {
  const { orderId = "" } = useParams();
  const routeState = useLocation().state as { reorderPriceComparison?: ReorderPriceComparison } | null;
  const [failure, setFailure] = useState<unknown>(null);
  const [paying, setPaying] = useState(false);
  const resource = useResource<Order>(useCallback(async () => unwrap(await customerApi.GET("/orders/{orderId}", { params: { path: { orderId } } })), [orderId]));

  async function pay(order: Order) {
    if (order.state !== "PENDING_PAYMENT") return;
    setPaying(true); setFailure(null);
    try {
      const attempt = unwrap(await customerApi.POST("/orders/{orderId}/payment-attempts", { params: { path: { orderId }, header: { "Idempotency-Key": idempotencyKey(`payment-attempt.${orderId}`), ...(await customerCsrfHeader()) } } }));
      attemptStorage.save(attempt);
      const config = unwrap(await customerApi.GET("/payment-config"));
      await requestTossStandardPayment(config.clientKey, { customerKey: attempt.customerKey, method: attempt.method, amount: attempt.amount, orderId: attempt.providerOrderId, orderName: attempt.orderName, successUrl: attempt.successUrl, failUrl: attempt.failUrl });
    } catch (error) { setFailure(error); setPaying(false); }
  }

  if (resource.state.status === "loading") return <div className="bfr-page"><RefreshLoading label="주문서를 불러오는 중" /></div>;
  if (resource.state.status === "failed") return <div className="bfr-page"><RefreshError error={resource.state.error} retry={resource.reload} /></div>;
  const order = resource.state.value;
  return (
    <div className="bfr-page bfr-checkout bfr-has-page-topbar">
      <RefreshMobileTopbar title="결제" backTo={`/app/orders/${order.publicReference}`} />
      {order.reservationExpiresAt ? <p className="bfr-lease" role="status"><Timer size={16} /><span><strong>예약 만료까지</strong>{shortDateTime.format(new Date(order.reservationExpiresAt))}</span></p> : null}
      <section className="bfr-checkout-store"><div><strong>{order.storeName}</strong><span>픽업 시간 {shortTime.format(new Date(order.pickupWindowStart))}</span></div><Link to={`/app/orders/${order.publicReference}`}>주문 내역 <ChevronRight size={15} /></Link></section>
      {routeState?.reorderPriceComparison?.hasPriceChanges ? <section className="bfr-price-change" role="status"><strong>현재 가격으로 다시 계산했어요</strong><span>이전 {won.format(routeState.reorderPriceComparison.sourceSubtotalKrw)} → 현재 {won.format(routeState.reorderPriceComparison.currentSubtotalKrw)}</span></section> : null}
      <section className="bfr-checkout-card">
        <header><h2>주문 메뉴</h2><span>{order.lines.length}개 품목</span></header>
        {order.lines.map((line) => <div className="bfr-checkout-line" key={line.orderLineId}><span><strong>{line.menuName}</strong><small>{line.optionNames.join(" · ") || "기본 옵션"} · {line.quantity}잔</small></span><b>{won.format(line.cashPaidKrw)}</b></div>)}
        <Pricing pricing={{ subtotalKrw: order.subtotalKrw, couponDiscountKrw: order.couponDiscountKrw, pointsAppliedKrw: order.pointsAppliedKrw, payableKrw: order.payableKrw }} />
      </section>
      <section className="bfr-payment-method"><header><h2>결제 수단</h2><Check size={17} /></header><div><span><CreditCard size={22} /></span><p><strong>카드 · 간편결제</strong><small>Toss Payments 통합결제창에서 선택</small></p><ChevronRight size={17} /></div><p><ShieldCheck size={14} />BeanFlow는 카드 번호를 저장하거나 처리하지 않습니다.</p></section>
      {order.state === "EXPIRED" ? <p className="bfr-inline-status" role="alert">결제 시간이 만료됐어요. 주문 상태에서 새 주문이 필요한지 확인해 주세요.</p> : null}
      {failure ? <RefreshError error={failure} /> : null}
      <Button variant="brand" size="xl" block loading={paying} disabled={order.state !== "PENDING_PAYMENT"} onClick={() => void pay(order)}>{paying ? "Toss 결제창을 여는 중" : `${won.format(order.payableKrw)} 결제하기`}</Button>
      <p className="bfr-legal">결제 버튼을 누르면 주문 내용과 결제 진행에 동의합니다.</p>
    </div>
  );
}

export function RefreshCustomerOrderDetailPage() {
  const { orderReference = "" } = useParams();
  const [order, setOrder] = useState<CustomerOrderDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [nonce, setNonce] = useState(0);
  const reload = useCallback(() => setNonce((value) => value + 1), []);

  useEffect(() => { setOrder(null); setError(null); }, [orderReference]);
  useEffect(() => {
    let disposed = false; let timer = 0;
    async function load() {
      try {
        const next = unwrap(await customerApi.GET("/me/orders/{orderReference}", { params: { path: { orderReference } } }));
        if (disposed) return; setOrder(next); setError(null);
        if (isLive(next.status) || ["REQUESTED", "PROCESSING"].includes(next.paymentRecovery?.state ?? "")) timer = window.setTimeout(() => void load(), 5_000);
      } catch (failure) { if (!disposed) setError(failure); }
    }
    void load(); return () => { disposed = true; window.clearTimeout(timer); };
  }, [orderReference, nonce]);

  if (!order && !error) return <div className="bfr-page"><RefreshLoading label="주문 상태를 확인하는 중" /></div>;
  if (!order) return <div className="bfr-page"><RefreshError error={error} retry={reload} /></div>;
  const pickupNote = pickupNumberNote(order.status);
  return (
    <div className="bfr-page bfr-order-detail bfr-has-page-topbar">
      <RefreshMobileTopbar title="주문 상세" backTo="/app/orders" />
      <section className="bfr-order-summary-card">
        <header className="bfr-order-status-head"><div><strong>{order.pickupNumber}</strong><span>{statusHeading(order.status)}</span><small>{order.storeName}</small></div><StatusText state={order.status} /></header>
        <dl><div><dt>주문 일시</dt><dd>{shortDateTime.format(new Date(order.orderedAt))}</dd></div><div><dt>주문 번호</dt><dd>{order.orderReference}</dd></div></dl>
      </section>
      {pickupNote ? <section className="bfr-pickup-card"><div className="bfr-pickup-number"><small>픽업 번호</small><strong>{order.pickupNumber}</strong><p>{pickupNote}</p></div></section> : null}
      <section className="bfr-timeline-card"><OrderTimeline order={order} /><p><Clock3 size={13} />픽업 시간 {shortDateTime.format(new Date(order.pickupWindowStart))}–{shortTime.format(new Date(order.pickupWindowEnd))}</p></section>
      <section className="bfr-transaction-card bfr-order-menu-card"><header><h2>주문 메뉴</h2><span>{order.lines.length}개</span></header>{order.lines.map((line) => <div className="bfr-transaction-line" key={line.lineSequence}><span><b>{line.quantity}</b><span><strong>{line.menuName}</strong><small>{line.optionNames.join(" · ") || "기본 옵션"}</small></span></span><b>{won.format(line.lineTotalKrw)}</b></div>)}</section>
      <section className="bfr-transaction-card bfr-order-pricing-card"><header><h2>거래 요약</h2><span>{shortDateTime.format(new Date(order.orderedAt))}</span></header><Pricing pricing={order.pricing} /></section>
      {order.paymentRecovery ? <PaymentRecovery recovery={order.paymentRecovery} /> : null}
      {error ? <RefreshError error={error} retry={reload} /> : null}
      <div className="bfr-order-actions">
        {order.allowedActions.includes("CANCEL") ? <RefreshCancelAction order={order} onDone={reload} /> : null}
        {order.allowedActions.includes("REORDER") ? <RefreshReorderAction order={order} /> : null}
        <Button block variant="ghost" onClick={reload}><RefreshCw size={16} />새로고침</Button>
      </div>
    </div>
  );
}

function RefreshCancelAction({ order, onDone }: { order: CustomerOrderDetail; onDone: () => void }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<CancellationReasonCode>("CHANGED_MIND");
  const [detail, setDetail] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const intent = useRef(new SubmissionIntent());
  async function submit() {
    const body = { reasonCode: reason, ...(detail.trim() ? { detail: detail.trim() } : {}) };
    setSubmitting(true); setFailure(null);
    try {
      unwrap(await customerApi.POST("/me/orders/{orderReference}/cancellations", { params: { path: { orderReference: order.orderReference }, header: { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ orderReference: order.orderReference, ...body })), ...(await customerCsrfHeader()) } }, body }));
      intent.current.complete(); setOpen(false); onDone();
    } catch (error) { if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") intent.current.rotate(); setFailure(error); } finally { setSubmitting(false); }
  }
  if (!open) return <Button block variant="ghost" onClick={() => setOpen(true)}>주문 취소</Button>;
  return <section className="bf-action-panel" aria-label="주문 취소"><h2>주문을 취소할까요?</h2><p>매장이 수락하기 전까지 주문 전체를 취소할 수 있어요.</p>{order.cancellationPreview ? <dl><div><dt>예상 현금 환불</dt><dd>{won.format(order.cancellationPreview.cashRefundAmountKrw)}</dd></div><div><dt>예상 포인트 복원</dt><dd>{order.cancellationPreview.restoredPoints.toLocaleString("ko-KR")}P</dd></div></dl> : null}<label>취소 사유<select value={reason} onChange={(event) => { setReason(event.target.value as CancellationReasonCode); intent.current.rotate(); }}><option value="CHANGED_MIND">마음이 바뀌었어요</option><option value="ORDER_MISTAKE">주문을 잘못했어요</option><option value="WAIT_TOO_LONG">기다리기 어려워요</option><option value="PICKUP_TIME_CONFLICT">픽업 시간이 안 맞아요</option><option value="PAYMENT_ISSUE">결제에 문제가 있어요</option><option value="OTHER">기타</option></select></label><label>자세한 사유 (선택)<textarea value={detail} maxLength={200} onChange={(event) => { setDetail(event.target.value); intent.current.rotate(); }} /></label>{failure ? <RefreshError error={failure} /> : null}<div><Button variant="ghost" onClick={() => setOpen(false)}>그대로 두기</Button><Button variant="brand" loading={submitting} onClick={() => void submit()}>주문 취소하기</Button></div></section>;
}

function RefreshReorderAction({ order }: { order: CustomerOrderDetail }) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [selectedSlot, setSelectedSlot] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const intent = useRef(new SubmissionIntent());
  const coupon = useCouponSelection(order.storeId);
  const slots = useResource<PickupSlot[]>(useCallback(async () => unwrap(await customerApi.GET("/stores/{storeId}/pickup-slots", { params: { path: { storeId: order.storeId } } })).items, [order.storeId]));
  async function reorder() {
    if (!selectedSlot) return;
    const body = { pickupSlotId: selectedSlot, pointsToUseKrw: 0, ...(coupon ? { couponIssuanceId: coupon.couponIssuanceId } : {}) };
    setSubmitting(true); setFailure(null);
    try {
      const created = unwrap(await customerApi.POST("/me/orders/{orderReference}/reorders", { params: { path: { orderReference: order.orderReference }, header: { "Idempotency-Key": intent.current.keyFor(JSON.stringify({ orderReference: order.orderReference, ...body })), ...(await customerCsrfHeader()) } }, body }));
      intent.current.complete(); couponSelection.clear(order.storeId);
      navigate(created.order.payableKrw > 0 ? `/app/checkout/${created.order.orderId}` : `/app/orders/${created.order.publicReference}`, { state: { reorderPriceComparison: created.priceComparison } });
    } catch (error) { if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") intent.current.rotate(); setFailure(error); } finally { setSubmitting(false); }
  }
  if (!open) return <Button block variant="secondary" onClick={() => setOpen(true)}><RotateCcw size={16} />같은 메뉴로 다시 주문</Button>;
  const available = slots.state.status === "ready" ? slots.state.value.filter((slot) => slot.remainingCapacity > 0) : [];
  const guidance = reorderFailure(failure);
  return <section className="bf-action-panel" aria-label="다시 주문"><h2>{order.storeName}에서 다시 주문할까요?</h2><p>메뉴와 옵션, 가격은 지금 판매 중인 조건으로 다시 확인합니다.</p>{slots.state.status === "loading" ? <RefreshLoading label="픽업 시간을 불러오는 중" /> : null}{slots.state.status === "failed" ? <RefreshError error={slots.state.error} retry={slots.reload} /> : null}{slots.state.status === "ready" && !available.length ? <RefreshEmpty title="고를 수 있는 픽업 시간이 없어요" description="잠시 뒤 다시 확인해 주세요." /> : null}<div className="bfr-slot-grid">{available.map((slot) => <button key={slot.pickupSlotId} type="button" aria-pressed={selectedSlot === slot.pickupSlotId} onClick={() => { intent.current.rotate(); setSelectedSlot(slot.pickupSlotId); }}><strong>{new Date(slot.startsAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}</strong><small>{slot.remainingCapacity}잔 가능</small></button>)}</div>{guidance ? <div className="bfr-decision" role="alert"><strong>{guidance.title}</strong><p>{guidance.description}</p>{guidance.items.length ? <ul>{guidance.items.map((item) => <li key={`${item.lineSequence}-${item.reason}`}>{item.label}</li>)}</ul> : null}</div> : failure ? <RefreshError error={failure} /> : null}<div><Button variant="ghost" onClick={() => setOpen(false)}>닫기</Button><Button variant="brand" loading={submitting} disabled={!selectedSlot} onClick={() => void reorder()}>이 시간으로 주문</Button></div></section>;
}

export function PaymentRecovery({ recovery }: { recovery: NonNullable<CustomerOrderDetail["paymentRecovery"]> }) {
  const copy = recovery.state === "SUCCEEDED" ? ["환불이 완료됐어요", "현금 환불 성공을 확인했습니다."] : recovery.state === "NOT_REQUIRED" ? ["추가 현금 환불이 없어요", "결제 전 취소 또는 이전 환불로 새 현금 환불이 필요하지 않습니다."] : recovery.noticeCode === "REFUND_DELAYED" ? ["환불 확인이 지연되고 있어요", "담당자가 결과를 확인하고 있습니다. 같은 요청을 다시 보내지 않아도 됩니다."] : ["환불 결과를 확인하고 있어요", "아직 성공도 실패도 아닙니다. 완료되면 주문 상태에 반영됩니다."];
  return <section className="bfr-recovery" role="status"><Clock3 size={18} /><div><strong>{copy[0]}</strong><p>{copy[1]}</p>{recovery.cancellationRequestedRefundAmountKrw !== undefined ? <small>요청 금액 {won.format(recovery.cancellationRequestedRefundAmountKrw)}</small> : null}</div></section>;
}

function OrderTimeline({ order }: { order: CustomerOrderDetail }) {
  const steps = [["paidAt", "결제 완료"], ["acceptedAt", "주문 접수"], ["preparingAt", "제조 중"], ["readyAt", "픽업 준비"], ["completedAt", "픽업 완료"]] as const;
  const lastOccurred = steps.reduce((last, [field], index) => order.lifecycle?.[field] ? index : last, -1);
  if (lastOccurred < 0) return null;
  return <ol className="bfr-order-timeline" aria-label="주문 진행 단계">{steps.map(([field, label], index) => { const timestamp = order.lifecycle?.[field]; return <li className={`${timestamp ? "is-complete" : ""} ${index === lastOccurred ? "is-current" : ""}`} aria-current={index === lastOccurred ? "step" : undefined} key={field}><span>{index + 1}</span><div><strong>{label}</strong><small>{timestamp ? shortTime.format(new Date(timestamp)) : "예정"}</small></div></li>; })}</ol>;
}

function Pricing({ pricing }: { pricing: { subtotalKrw: number; couponDiscountKrw: number; pointsAppliedKrw: number; payableKrw: number } }) {
  return <dl className="bfr-pricing"><div><dt>상품 금액</dt><dd>{won.format(pricing.subtotalKrw)}</dd></div>{pricing.couponDiscountKrw > 0 ? <div><dt>쿠폰 할인</dt><dd>−{won.format(pricing.couponDiscountKrw)}</dd></div> : null}{pricing.pointsAppliedKrw > 0 ? <div><dt>포인트 사용</dt><dd>−{won.format(pricing.pointsAppliedKrw)}</dd></div> : null}<div><dt>결제 금액</dt><dd>{won.format(pricing.payableKrw)}</dd></div></dl>;
}

function isLive(status: CustomerOrderDetail["status"]) { return ["PENDING_PAYMENT", "PAID", "ACCEPTED", "PREPARING", "READY"].includes(status); }
function statusHeading(status: CustomerOrderDetail["status"]) { const labels: Record<CustomerOrderDetail["status"], string> = { PENDING_PAYMENT: "결제를 기다리고 있어요", PAID: "매장 접수를 기다리고 있어요", ACCEPTED: "주문이 접수됐어요", PREPARING: "메뉴를 준비하고 있어요", READY: "픽업할 준비가 끝났어요", COMPLETED: "픽업이 완료됐어요", CANCELLED: "취소된 주문이에요", REJECTED: "매장에서 주문을 거절했어요", EXPIRED: "결제 시간이 만료됐어요" }; return labels[status]; }
