import { ArrowRight, CalendarDays, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { Button, ButtonLink, EmptyState, LoadingState, PageHeading, Tab, TabList, TabPanel, Tabs, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { shortDateTime, won } from "../../lib/format";

type CustomerOrderPage = components["schemas"]["CustomerOrderPage"];
type CustomerOrderSummary = components["schemas"]["CustomerOrderSummary"];
type CustomerOrderStatus = "ACTIVE" | "PAST";

function initialDates() {
  const now = new Date();
  return { from: seoulDate(new Date(now.getTime() - (29 * 24 * 60 * 60 * 1_000))), to: seoulDate(now) };
}

export function seoulDate(value: Date) {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(value);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((entry) => entry.type === type)?.value;
  return `${part("year")}-${part("month")}-${part("day")}`;
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
  const generation = useRef(0);

  const load = useCallback(async (cursor?: string, append = false) => {
    const requestGeneration = ++generation.current;
    append ? setLoadingMore(true) : setPage(null); setError(null);
    try {
      const next = unwrap(await customerApi.GET("/me/orders", { params: { query: { status, from, to, cursor, limit: 20 } } }));
      if (generation.current !== requestGeneration) return;
      setPage((current) => append && current ? { items: [...current.items, ...next.items], page: next.page } : next);
    } catch (failure) { if (generation.current === requestGeneration) setError(failure); }
    finally { if (generation.current === requestGeneration) setLoadingMore(false); }
  }, [from, status, to]);
  useEffect(() => { void load(); }, [load]);

  function update(next: Partial<{ status: CustomerOrderStatus; from: string; to: string }>) {
    const values = new URLSearchParams(searchParams);
    values.set("status", next.status ?? status); values.set("from", next.from ?? from); values.set("to", next.to ?? to);
    setSearchParams(values, { replace: true });
  }
  const content = <>{!page && !error ? <LoadingState label="주문을 불러오는 중" /> : null}{error ? <ErrorState error={error} retry={() => void load()} /> : null}{page?.items.length === 0 ? <EmptyState title={status === "ACTIVE" ? "진행 중인 주문이 없어요" : "이 기간의 주문이 없어요"} description={status === "ACTIVE" ? "새 주문을 시작하면 픽업 상태가 여기에 표시됩니다." : "조회 기간을 넓혀 다시 확인해 보세요."} action={<ButtonLink to="/app/stores">매장 찾기</ButtonLink>} /> : null}{page?.items.length ? <section className="customer-order-list" aria-label={status === "ACTIVE" ? "진행 중인 주문" : "지난 주문"}>{page.items.map((order) => <OrderRow key={order.orderReference} order={order} active={status === "ACTIVE"} />)}</section> : null}{page?.page.nextCursor ? <Button block variant="secondary" loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}><RefreshCw size={16} />{loadingMore ? "더 불러오는 중" : "주문 더 보기"}</Button> : null}</>;
  return <div className="customer-page customer-orders-page"><PageHeading title="주문 내역" /><Tabs value={status} onValueChange={(value) => update({ status: value as CustomerOrderStatus })}><TabList label="주문 상태"><Tab value="ACTIVE">진행 중</Tab><Tab value="PAST">지난 주문</Tab></TabList><div className="order-date-filter surface-card"><CalendarDays size={18} /><TextField label="조회 시작일" id="customer-orders-from" type="date" value={from} max={to} onValueChange={(value) => update({ from: value })} /><TextField label="조회 종료일" id="customer-orders-to" type="date" value={to} min={from} onValueChange={(value) => update({ to: value })} /></div><TabPanel value="ACTIVE">{status === "ACTIVE" ? content : null}</TabPanel><TabPanel value="PAST">{status === "PAST" ? content : null}</TabPanel></Tabs></div>;
}

function OrderRow({ order, active }: { order: CustomerOrderSummary; active: boolean }) {
  const hasRefund = order.allowedActions.includes("VIEW_REFUND");
  return <Link className={`customer-order-row surface-card ${active ? "is-active" : ""}`} to={`/app/orders/${order.orderReference}`}><div className="customer-order-row-head"><StatusText state={order.status} /><span>{order.storeName}{hasRefund ? " · 환불 내역 확인" : ""}</span></div>{active ? <strong className="pickup-number">{order.pickupNumber}</strong> : null}<div className="customer-order-row-body"><div><strong>{order.itemSummary}</strong><span>{shortDateTime.format(new Date(order.orderedAt))} · {order.pickupNumber}</span></div><div><strong>{won.format(order.totalAmountKrw)}</strong><ArrowRight size={18} /></div></div></Link>;
}
