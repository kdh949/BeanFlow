import { ArrowLeft, ArrowRight, CalendarDays, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { shortDateTime, shortTime, won } from "../../lib/format";
import { Button, ButtonLink } from "../../design-system";

type CustomerOrderPage = components["schemas"]["CustomerOrderPage"];
type CustomerOrderSummary = components["schemas"]["CustomerOrderSummary"];
type CustomerOrderDetail = components["schemas"]["CustomerOrderDetail"];
type CustomerOrderStatus = "ACTIVE" | "PAST";

function initialDates() {
  const now = new Date();
  const to = seoulDate(now);
  return { from: seoulDate(new Date(now.getTime() - (29 * 24 * 60 * 60 * 1_000))), to };
}

export function seoulDate(value: Date) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(value);
  const valueOf = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value;
  return `${valueOf("year")}-${valueOf("month")}-${valueOf("day")}`;
}

export function CustomerOrdersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const defaults = initialDates();
  const status = searchParams.get("status") === "PAST" ? "PAST" : "ACTIVE";
  const from = searchParams.get("from") ?? defaults.from;
  const to = searchParams.get("to") ?? defaults.to;
  const [page, setPage] = useState<CustomerOrderPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const load = useCallback(async (cursor?: string, append = false) => {
    if (append) setLoadingMore(true);
    else setPage(null);
    setError(null);
    try {
      const result = await customerApi.GET("/me/orders", {
        params: { query: { status, from, to, cursor, limit: 20 } },
      });
      const next = unwrap(result);
      setPage((current) => append && current
        ? { items: [...current.items, ...next.items], page: next.page }
        : next);
    } catch (failure) {
      setError(failure);
    } finally {
      setLoadingMore(false);
    }
  }, [from, status, to]);

  useEffect(() => { void load(); }, [load]);

  function update(next: Partial<{ status: CustomerOrderStatus; from: string; to: string }>) {
    const values = new URLSearchParams(searchParams);
    values.set("status", next.status ?? status);
    values.set("from", next.from ?? from);
    values.set("to", next.to ?? to);
    setSearchParams(values, { replace: true });
  }

  return (
    <div className="customer-page customer-orders-page">
      <PageTitle eyebrow="MY ORDERS" title="주문" description="진행 중인 픽업과 지난 주문을 한곳에서 확인하세요." />
      <div className="order-tabs" role="tablist" aria-label="주문 상태">
        <button type="button" role="tab" aria-selected={status === "ACTIVE"} onClick={() => update({ status: "ACTIVE" })}>진행 중</button>
        <button type="button" role="tab" aria-selected={status === "PAST"} onClick={() => update({ status: "PAST" })}>지난 주문</button>
      </div>
      <div className="order-date-filter surface-card">
        <CalendarDays size={18} aria-hidden="true" />
        <label htmlFor="customer-orders-from">조회 시작일</label>
        <input id="customer-orders-from" type="date" value={from} max={to} onChange={(event) => update({ from: event.target.value })} />
        <label htmlFor="customer-orders-to">조회 종료일</label>
        <input id="customer-orders-to" type="date" value={to} min={from} onChange={(event) => update({ to: event.target.value })} />
      </div>
      {!page && !error ? <LoadingState label="주문을 불러오는 중" /> : null}
      {error ? <ErrorState error={error} retry={() => void load()} /> : null}
      {page?.items.length === 0 ? (
        <EmptyState
          title={status === "ACTIVE" ? "진행 중인 주문이 없어요" : "이 기간의 주문이 없어요"}
          description={status === "ACTIVE" ? "새 주문을 시작하면 픽업 상태가 여기에 표시됩니다." : "조회 기간을 넓혀 다시 확인해 보세요."}
          action={<ButtonLink to="/app">매장 찾기</ButtonLink>}
        />
      ) : null}
      {page?.items.length ? (
        <section className="customer-order-list" aria-label={status === "ACTIVE" ? "진행 중인 주문" : "지난 주문"}>
          {page.items.map((order) => <CustomerOrderRow key={order.orderReference} order={order} active={status === "ACTIVE"} />)}
        </section>
      ) : null}
      {page?.page.nextCursor ? (
        <Button block variant="secondary" type="button" loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>
          <RefreshCw size={16} className={loadingMore ? "spin" : undefined} /> {loadingMore ? "더 불러오는 중" : "주문 더 보기"}
        </Button>
      ) : null}
    </div>
  );
}

function CustomerOrderRow({ order, active }: { order: CustomerOrderSummary; active: boolean }) {
  return (
    <Link className={`customer-order-row surface-card ${active ? "is-active" : ""}`} to={`/app/orders/${order.orderReference}`}>
      <div className="customer-order-row-head">
        <StatusBadge state={order.status} />
        <span>{order.storeName}</span>
      </div>
      {active ? <strong className="pickup-number">{order.pickupNumber}</strong> : null}
      <div className="customer-order-row-body">
        <div>
          <strong>{order.itemSummary}</strong>
          <span>{shortDateTime.format(new Date(order.orderedAt))} · {order.pickupNumber}</span>
        </div>
        <div><strong>{won.format(order.totalAmountKrw)}</strong><ArrowRight size={18} aria-hidden="true" /></div>
      </div>
    </Link>
  );
}

export function CustomerOrderDetailPage() {
  const { orderReference = "" } = useParams();
  const [order, setOrder] = useState<CustomerOrderDetail | null>(null);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const result = await customerApi.GET("/me/orders/{orderReference}", {
        params: { path: { orderReference } },
      });
      setOrder(unwrap(result));
    } catch (failure) {
      setError(failure);
    }
  }, [orderReference]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    const timer = window.setInterval(() => void load(), 5_000);
    return () => window.clearInterval(timer);
  }, [load]);

  if (!order && !error) return <LoadingState label="주문 상태를 확인하는 중" />;
  if (error && !order) return <ErrorState error={error} retry={() => void load()} />;
  if (!order) return null;
  return (
    <div className="customer-page customer-order-detail-page">
      <Link className="back-link" to="/app/orders"><ArrowLeft size={17} /> 주문 목록</Link>
      <PageTitle eyebrow="ORDER" title="주문 상태" action={<StatusBadge state={order.status} />} />
      <section className="active-order-card surface-card">
        <div className="active-order-card-head"><StatusBadge state={order.status} /><span>{order.storeName}</span></div>
        <strong className="pickup-number">{order.pickupNumber}</strong>
        <p>픽업대에서 번호를 확인해 주세요.</p>
        <OrderTimeline state={order.status} />
        <dl className="pickup-window">
          <div><dt>픽업 시간</dt><dd>{shortDateTime.format(new Date(order.pickupWindowStart))}–{shortTime.format(new Date(order.pickupWindowEnd))}</dd></div>
          <div><dt>주문 번호</dt><dd>{order.orderReference}</dd></div>
        </dl>
      </section>
      <section className="surface-card order-detail-lines">
        <h2>주문 내역</h2>
        {order.lines.map((line) => (
          <div key={line.lineSequence}>
            <strong>{line.quantity}</strong>
            <span><b>{line.menuName}</b><small>{[...line.optionNames, `${line.quantity}잔`].join(" · ")}</small></span>
            <b>{won.format(line.lineTotalKrw)}</b>
          </div>
        ))}
        <div className="order-detail-total"><span>결제 금액</span><strong>{won.format(order.totalAmountKrw)}</strong></div>
      </section>
      {order.paymentRecovery ? <section className="recovery-note" role="status"><RefreshCw size={17} /><div><strong>환불 처리 상태</strong><span>{order.paymentRecovery.noticeCode ? "확인이 지연되고 있어요. 같은 요청을 반복하지 않아도 됩니다." : `현재 ${order.paymentRecovery.state} 상태입니다.`}</span></div></section> : null}
      {order.allowedActions.includes("CANCEL") ? <section className="order-action-note" role="status"><div><strong>취소 가능한 주문</strong><span>매장에서 주문을 수락하기 전까지 취소할 수 있어요.</span></div></section> : null}
      {error ? <ErrorState error={error} retry={() => void load()} /> : null}
      <Button block variant="ghost" type="button" onClick={() => void load()}><RefreshCw size={16} /> 새로고침</Button>
    </div>
  );
}

type OrderTimelineModel = {
  kind: "pending" | "progress" | "terminal";
  activeIndex: number | null;
  terminalLabel?: string;
};

export function customerOrderTimelineModel(state: CustomerOrderDetail["status"]): OrderTimelineModel {
  switch (state) {
    case "PENDING_PAYMENT": return { kind: "pending", activeIndex: null };
    case "PAID": return { kind: "progress", activeIndex: 0 };
    case "ACCEPTED": return { kind: "progress", activeIndex: 1 };
    case "PREPARING": return { kind: "progress", activeIndex: 2 };
    case "READY": return { kind: "progress", activeIndex: 3 };
    case "COMPLETED": return { kind: "progress", activeIndex: 4 };
    case "CANCELLED": return { kind: "terminal", activeIndex: null, terminalLabel: "취소된 주문입니다" };
    case "REJECTED": return { kind: "terminal", activeIndex: null, terminalLabel: "매장에서 거절한 주문입니다" };
    case "EXPIRED": return { kind: "terminal", activeIndex: null, terminalLabel: "결제 시간이 만료된 주문입니다" };
  }
}

function OrderTimeline({ state }: { state: CustomerOrderDetail["status"] }) {
  const model = customerOrderTimelineModel(state);
  if (model.kind === "terminal") return <div className="terminal-order-state" role="status"><strong>{model.terminalLabel}</strong></div>;
  return <ol className="order-timeline" aria-label="주문 진행 단계">
    {["결제 완료", "주문 접수", "제조 중", "픽업 준비", "픽업 완료"].map((label, index) => (
      <li key={label} className={model.activeIndex !== null && index <= model.activeIndex ? "is-active" : ""}><span>{index + 1}</span><strong>{label}</strong></li>
    ))}
  </ol>;
}
