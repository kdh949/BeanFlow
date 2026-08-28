import {
  ArrowLeft,
  Check,
  Coffee,
  MapPin,
  Navigation,
  ShoppingBag,
  Trash2,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { useStore } from "../../features/discovery/useStore";
import { nextPickupLabel, operatingStatusLabel } from "../../features/discovery/storeDisplay";
import { couponSelection, useCouponSelection } from "../../features/customer/couponSelection";
import { type CartLine, cart, cartItemCount, useCart } from "../../features/ordering/cart";
import { orderConflictGuidance, shouldRotateIdempotencyKey } from "../../features/ordering/orderConflicts";
import { useResource } from "../../features/shared/useResource";
import { won } from "../../lib/format";
import { RefreshEmpty, RefreshError, RefreshLoading, RefreshMobileTopbar, RefreshPageHeading } from "./RefreshShared";
import { Button, ButtonLink, Checkbox, QuantityStepper, RadioCard, RadioGroup } from "../../design-system";

type CustomerStore = components["schemas"]["CustomerStore"];
type Menu = components["schemas"]["Menu"];
type PickupSlot = components["schemas"]["PickupSlot"];
type Order = components["schemas"]["Order"];
type OrderQuote = components["schemas"]["OrderQuote"];
type Catalog = { store: CustomerStore; menus: Menu[]; slots: PickupSlot[] };

export function RefreshStoreDetailPage() {
  const { storeId = "" } = useParams();
  const cartState = useCart();
  const load = useCallback(async (): Promise<Catalog> => {
    const [storeResult, menuResult, slotsResult] = await Promise.all([
      customerApi.GET("/stores/{storeId}", { params: { path: { storeId } } }),
      customerApi.GET("/stores/{storeId}/menus", { params: { path: { storeId } } }),
      customerApi.GET("/stores/{storeId}/pickup-slots", { params: { path: { storeId } } }),
    ]);
    return { store: unwrap(storeResult), menus: unwrap(menuResult).items, slots: unwrap(slotsResult).items };
  }, [storeId]);
  const { state, reload } = useResource<Catalog>(load);

  if (state.status === "loading") return <div className="bfr-page"><RefreshLoading label="메뉴와 픽업 시간을 준비하는 중" /></div>;
  if (state.status === "failed" && state.error instanceof ApiRequestError && state.error.status === 404) {
    return <div className="bfr-page"><BackLink to="/app/stores">매장 찾기</BackLink><RefreshEmpty title="지금은 주문할 수 없는 매장이에요" description="주소가 바뀌었거나 더 이상 주문을 받지 않는 매장입니다." action={<ButtonLink variant="brand" to="/app/stores">다른 매장 찾기</ButtonLink>} /></div>;
  }
  if (state.status === "failed") return <div className="bfr-page"><RefreshError error={state.error} retry={reload} /></div>;

  const { store, menus, slots } = state.value;
  const orderable = store.orderingAvailable && store.pickupAvailable && slots.some((slot) => slot.remainingCapacity > 0);
  const groups = groupMenus(menus);
  const count = cartItemCount(cartState);
  return (
    <div className="bfr-page bfr-catalog bfr-has-page-topbar">
      <RefreshMobileTopbar title="BeanFlow" backTo="/app/stores" brand />
      <section className="bfr-store-hero">
        <span className="bfr-store-hero__media">{store.image ? <img src={store.image.url} alt="" /> : <Coffee size={42} />}</span>
        <div><h1>{store.name}</h1><p>{store.customerDisplay.addressLine ?? "주소 정보 없음"}</p></div>
      </section>
      <section className="bfr-store-facts" aria-label="매장 이용 안내">
        <div><span>주문</span><strong>{store.orderingAvailable ? "주문 가능" : "주문 쉬는 중"}</strong></div>
        <div><span>운영시간</span><strong>{operatingStatusLabel(store.customerDisplay.operatingStatus)}</strong></div>
        <div><span>픽업</span><strong>{nextPickupLabel(store.nextPickupWindow)}</strong></div>
      </section>
      {store.customerDisplay.directionsHint ? <p className="bfr-direction"><Navigation size={16} />{store.customerDisplay.directionsHint}</p> : null}
      {!store.orderingAvailable ? <p className="bfr-inline-status" role="status">운영시간과 별개로 이 매장은 현재 주문을 받지 않아요.</p> : !orderable ? <p className="bfr-inline-status" role="status">지금은 픽업 시간이 모두 마감됐어요.</p> : null}

      {slots.length ? <section className="bfr-pickup-strip" aria-label="픽업 시간"><header><h2>픽업 시간</h2><span>서버 제공 시간</span></header><div>{slots.slice(0, 6).map((slot, index) => <span className={index === 0 ? "is-first" : ""} key={slot.pickupSlotId}>{new Date(slot.startsAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}</span>)}</div></section> : null}

      <nav className="bfr-category-tabs" aria-label="메뉴 카테고리">{groups.map((group) => <a key={group.key} href={`#bfr-menu-${group.key}`}>{group.name}</a>)}</nav>
      <div className="bfr-menu-groups">
        {menus.length === 0 ? <RefreshEmpty title="판매 중인 메뉴가 없어요" description="잠시 뒤 다시 확인해 주세요." /> : groups.map((group) => (
          <section id={`bfr-menu-${group.key}`} key={group.key} className="bfr-menu-group">
            <header><h2>{group.name}</h2><span>{group.items.length}개</span></header>
            {group.items.map((menu) => <RefreshMenuRow key={menu.menuId} menu={menu} storeId={storeId} storeName={store.name} orderable={orderable} />)}
          </section>
        ))}
      </div>
      {count > 0 ? <div className="bfr-floating-cart"><ButtonLink variant="brand" size="xl" block to="/app/cart"><ShoppingBag size={18} />장바구니 {count}개 보기</ButtonLink></div> : null}
    </div>
  );
}

function RefreshMenuRow({ menu, storeId, storeName, orderable }: { menu: Menu; storeId: string; storeName: string; orderable: boolean }) {
  const [open, setOpen] = useState(false);
  const [quantity, setQuantity] = useState(1);
  const [optionIds, setOptionIds] = useState<string[]>([]);
  const [conflict, setConflict] = useState<{ currentStoreName: string; line: CartLine } | null>(null);
  const [added, setAdded] = useState(false);
  const options = menu.options ?? [];
  const optionPrice = options.filter((option) => optionIds.includes(option.optionId)).reduce((sum, option) => sum + option.additionalPriceKrw, 0);
  const unitPrice = menu.basePriceKrw + optionPrice;
  const selectable = menu.available && orderable;
  function line(): CartLine {
    return { menuId: menu.menuId, optionIds, quantity, display: { menuName: menu.name, optionNames: options.filter((option) => optionIds.includes(option.optionId)).map((option) => option.name), unitPriceKrw: unitPrice, imageUrl: menu.image?.url } };
  }
  function add() {
    const next = line();
    const result = cart.add({ storeId, storeName }, next);
    if (result.outcome === "other-store") { setConflict({ currentStoreName: result.currentStoreName, line: next }); return; }
    setAdded(true); setOpen(false); setQuantity(1); setOptionIds([]);
  }
  return (
    <article className={`bfr-menu-row ${!menu.available ? "is-soldout" : ""}`}>
      <Button block variant="ghost" disabled={!selectable} aria-expanded={open} onClick={() => setOpen((current) => !current)}>
        <span className="bfr-menu-row__media">{menu.image ? <img src={menu.image.url} alt="" /> : <Coffee size={26} />}</span>
        <span><strong>{menu.name}</strong>{menu.description ? <small>{menu.description}</small> : null}<b>{won.format(menu.basePriceKrw)}{menu.available ? "" : " · 품절"}</b></span>
        <span className="bfr-menu-row__add">{open ? <Check size={17} /> : "+"}</span>
      </Button>
      {open ? <div className="bfr-menu-config">
        {options.length ? <fieldset><legend>옵션 선택</legend>{options.map((option) => <Checkbox key={option.optionId} variant="card" label={`${option.name}${option.available ? "" : " · 품절"}`} trailing={`+${won.format(option.additionalPriceKrw)}`} checked={optionIds.includes(option.optionId)} disabled={!option.available} onCheckedChange={() => setOptionIds((current) => current.includes(option.optionId) ? current.filter((id) => id !== option.optionId) : [...current, option.optionId])} />)}</fieldset> : null}
        <div className="bfr-config-actions"><QuantityStepper value={quantity} label={`${menu.name} 수량`} onChange={setQuantity} /><Button variant="brand" onClick={add}>{won.format(unitPrice * quantity)} 담기</Button></div>
        <p>금액과 재고는 주문할 때 매장 기준으로 다시 확인해요.</p>
      </div> : null}
      {added ? <p className="bfr-success-note" role="status">장바구니에 담았어요.</p> : null}
      {conflict ? <div className="bfr-decision" role="alertdialog" aria-label="다른 매장 장바구니"><strong>{conflict.currentStoreName} 주문이 이미 담겨 있어요</strong><p>한 번에 한 매장만 주문할 수 있습니다.</p><div><Button variant="ghost" onClick={() => setConflict(null)}>그대로 두기</Button><Button variant="brand" onClick={() => { cart.replaceWith({ storeId, storeName }, conflict.line); setConflict(null); setAdded(true); setOpen(false); }}>비우고 담기</Button></div></div> : null}
    </article>
  );
}

type QuoteState = { status: "idle" } | { status: "loading" } | { status: "ready"; quote: OrderQuote } | { status: "failed"; error: unknown } | { status: "stale"; quote: OrderQuote };

export function RefreshCartPage() {
  const state = useCart();
  if (state.status === "corrupt") return <div className="bfr-page"><RefreshPageHeading title="장바구니" /><div className="bfr-decision" role="alert"><strong>장바구니 정보를 읽지 못했어요</strong><p>이 기기에 저장된 정보가 손상됐습니다. 비운 뒤 다시 담아 주세요.</p><Button variant="brand" onClick={() => cart.clear()}>장바구니 비우기</Button></div></div>;
  if (state.status === "empty") return <div className="bfr-page"><RefreshPageHeading title="장바구니" /><RefreshEmpty title="담은 메뉴가 없어요" description="매장을 골라 메뉴를 담으면 여기에서 픽업 시간을 정할 수 있어요." action={<ButtonLink variant="brand" to="/app/stores">매장 찾기</ButtonLink>} /></div>;
  return <RefreshCartContents storeId={state.cart.storeId} savedStoreName={state.cart.storeName} lines={state.cart.lines} />;
}

function RefreshCartContents({ storeId, savedStoreName, lines }: { storeId: string; savedStoreName: string; lines: CartLine[] }) {
  const navigate = useNavigate();
  const [selectedSlot, setSelectedSlot] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const [quoteState, setQuoteState] = useState<QuoteState>({ status: "idle" });
  const [quoteReload, setQuoteReload] = useState(0);
  const quoteRequest = useRef(0);
  const intent = useRef(new SubmissionIntent());
  const store = useStore(storeId);
  const storeName = store.state.status === "ready" ? store.state.value.name : savedStoreName;
  const storeAcceptsOrders = store.state.status !== "ready" || store.state.value.orderingAvailable;
  const selectedCoupon = useCouponSelection(storeId);
  const slots = useResource<PickupSlot[]>(useCallback(async () => unwrap(await customerApi.GET("/stores/{storeId}/pickup-slots", { params: { path: { storeId } } })).items, [storeId]));

  useEffect(() => {
    intent.current.rotate(); setFailure(null); const requestId = ++quoteRequest.current;
    if (!selectedSlot || !storeAcceptsOrders) { setQuoteState({ status: "idle" }); return; }
    setQuoteState({ status: "loading" });
    const timer = window.setTimeout(() => void (async () => {
      try {
        const quote = unwrap(await customerApi.POST("/me/order-quotes", { params: { header: await customerCsrfHeader() }, body: editableOrderInput(storeId, selectedSlot, lines, selectedCoupon?.couponIssuanceId) })) as OrderQuote;
        if (quoteRequest.current === requestId) setQuoteState({ status: "ready", quote });
      } catch (error) { if (quoteRequest.current === requestId) setQuoteState({ status: "failed", error }); }
    })(), 250);
    return () => window.clearTimeout(timer);
  }, [storeId, selectedSlot, lines, selectedCoupon?.couponIssuanceId, storeAcceptsOrders, quoteReload]);

  async function createOrder() {
    if (!selectedSlot || quoteState.status !== "ready" || !storeAcceptsOrders) return;
    const body = { ...editableOrderInput(storeId, selectedSlot, lines, selectedCoupon?.couponIssuanceId), expectedQuoteFingerprint: quoteState.quote.quoteFingerprint };
    setSubmitting(true); setFailure(null);
    try {
      const created = unwrap(await customerApi.POST("/orders", { params: { header: { "Idempotency-Key": intent.current.keyFor(JSON.stringify(body)), ...(await customerCsrfHeader()) } }, body })).order as Order;
      intent.current.complete(); cart.clear(); couponSelection.clear(storeId);
      navigate(created.payableKrw > 0 ? `/app/checkout/${created.orderId}` : `/app/orders/${created.publicReference}`);
    } catch (error) {
      const current = staleQuote(error);
      if (current) setQuoteState({ status: "stale", quote: current });
      else { if (shouldRotateIdempotencyKey(error)) intent.current.rotate(); setFailure(error); }
    } finally { setSubmitting(false); }
  }

  const quote = quoteState.status === "ready" || quoteState.status === "stale" ? quoteState.quote : null;
  const availableSlots = slots.state.status === "ready" ? slots.state.value.filter((slot) => slot.remainingCapacity > 0) : [];
  const guidance = orderConflictGuidance(failure);
  return (
    <div className="bfr-page bfr-cart bfr-has-page-topbar">
      <RefreshMobileTopbar title="BeanFlow" brand />
      <RefreshPageHeading title="장바구니" description={`${storeName}에서 픽업합니다.`} />
      {store.state.status === "ready" ? <section className="bfr-cart-store"><div><MapPin size={16} /><span><strong>{store.state.value.name}</strong><small>{store.state.value.customerDisplay.addressLine ?? "주소 정보 없음"}</small></span></div><span>{store.state.value.orderingAvailable ? "주문 가능" : "주문 쉬는 중"}</span></section> : null}
      {store.state.status === "failed" ? <p className="bfr-inline-status" role="status">매장 안내를 불러오지 못했어요. 주문 요청에서 가능 여부를 다시 확인합니다.</p> : null}
      <section className="bfr-cart-lines" aria-label="담은 메뉴">
        <h2>주문 메뉴</h2>{lines.map((line, index) => <div key={`${line.menuId}-${line.optionIds.join("-")}`}><span className="bfr-cart-line-media">{line.display.imageUrl ? <img src={line.display.imageUrl} alt="" /> : <Coffee size={21} aria-hidden="true" />}</span><span><strong>{line.display.menuName}</strong><small>{line.display.optionNames.join(" · ") || "기본 옵션"}</small></span><QuantityStepper value={line.quantity} min={0} label={`${line.display.menuName} 수량`} onChange={(value) => cart.setQuantity(index, value)} /><b>{quote ? won.format(quote.lines[index]?.lineTotalKrw ?? 0) : <Trash2 size={17} />}</b></div>)}
        {quoteState.status === "idle" ? <p>픽업 시간을 선택하면 서버가 현재 금액과 혜택을 확인해요.</p> : null}
        {quoteState.status === "loading" ? <RefreshLoading label="현재 주문 금액을 확인하는 중" /> : null}
        {quoteState.status === "failed" ? <RefreshError error={quoteState.error} retry={() => setQuoteReload((value) => value + 1)} /> : null}
      </section>
      <section className="bfr-slot-section">{slots.state.status === "loading" ? <RefreshLoading label="픽업 시간을 불러오는 중" /> : null}{slots.state.status === "failed" ? <RefreshError error={slots.state.error} retry={slots.reload} /> : null}{slots.state.status === "ready" && availableSlots.length === 0 ? <RefreshEmpty title="고를 수 있는 픽업 시간이 없어요" description="잠시 뒤 다시 확인해 주세요." /> : null}{availableSlots.length ? <div className="bfr-slot-grid"><RadioGroup label="픽업 시간" value={selectedSlot} disabled={!storeAcceptsOrders} onValueChange={(value) => { if (selectedSlot !== value) intent.current.rotate(); setSelectedSlot(value); }}>{availableSlots.map((slot) => <RadioCard key={slot.pickupSlotId} value={slot.pickupSlotId} label={new Date(slot.startsAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })} description={`${slot.remainingCapacity}잔 가능`} />)}</RadioGroup></div> : null}</section>
      <section className="bfr-coupon-row"><span><small>쿠폰</small><strong>{selectedCoupon?.label ?? "선택하지 않음"}</strong></span>{selectedCoupon ? <Button variant="ghost" onClick={() => couponSelection.clear(storeId)}>선택 해제</Button> : <ButtonLink variant="ghost" to={`/app/coupons?storeId=${encodeURIComponent(storeId)}`}>쿠폰 보기</ButtonLink>}</section>
      <section className="bfr-coupon-row"><span><small>포인트</small><strong>{quote ? `${quote.pricing.pointsAppliedKrw.toLocaleString("ko-KR")}P 사용` : "견적 확인 후 적용"}</strong></span><span className="bfr-muted-note">서버 계산</span></section>
      {quote ? <section className="bfr-transaction-card bfr-cart-pricing"><RefreshQuotePricing quote={quote} /></section> : null}
      {guidance ? <div className="bfr-decision" role="alert"><strong>{guidance.title}</strong><p>{guidance.description}</p></div> : failure ? <RefreshError error={failure} /> : null}
      {quoteState.status === "stale" ? <div className="bfr-decision" role="alert"><strong>주문 금액과 조건이 변경됐어요</strong><p>새 서버 견적을 확인한 뒤 다시 제출해 주세요.</p><Button variant="brand" onClick={() => { intent.current.rotate(); setFailure(null); setQuoteState({ status: "ready", quote: quoteState.quote }); }}>변경 내용 확인</Button></div> : null}
      <Button variant="brand" size="xl" block loading={submitting} disabled={!selectedSlot || !storeAcceptsOrders || quoteState.status !== "ready"} onClick={() => void createOrder()}>{quoteState.status === "ready" ? `${won.format(quoteState.quote.pricing.payableKrw)} 주문하기` : "견적 확인 후 주문하기"}</Button>
    </div>
  );
}

function RefreshQuotePricing({ quote }: { quote: OrderQuote }) {
  return <dl className="bfr-pricing"><div><dt>상품 금액</dt><dd>{won.format(quote.pricing.subtotalKrw)}</dd></div>{quote.pricing.couponDiscountKrw ? <div><dt>쿠폰 할인</dt><dd>−{won.format(quote.pricing.couponDiscountKrw)}</dd></div> : null}{quote.pricing.pointsAppliedKrw ? <div><dt>포인트 사용</dt><dd>−{won.format(quote.pricing.pointsAppliedKrw)}</dd></div> : null}<div><dt>결제 금액</dt><dd>{won.format(quote.pricing.payableKrw)}</dd></div></dl>;
}

function editableOrderInput(storeId: string, pickupSlotId: string, lines: CartLine[], couponIssuanceId?: string) {
  return { storeId, pickupSlotId, lines: lines.map((line) => ({ menuId: line.menuId, optionIds: line.optionIds, quantity: line.quantity })), pointsToUseKrw: 0, ...(couponIssuanceId ? { couponIssuanceId } : {}) };
}

function staleQuote(error: unknown): OrderQuote | null {
  if (!(error instanceof ApiRequestError) || error.code !== "ORDER_QUOTE_STALE") return null;
  const quote = error.currentQuote;
  if (!quote || typeof quote !== "object") return null;
  const candidate = quote as Partial<OrderQuote>;
  return typeof candidate.quoteFingerprint === "string" && /^[0-9a-f]{64}$/.test(candidate.quoteFingerprint) && candidate.pricing && Array.isArray(candidate.lines) ? candidate as OrderQuote : null;
}

function groupMenus(menus: Menu[]) {
  const groups = new Map<string, Menu[]>();
  for (const menu of menus) { const key = menu.displayCategory?.trim() || "메뉴"; groups.set(key, [...(groups.get(key) ?? []), menu]); }
  return [...groups.entries()].map(([name, items], index) => ({ name, items, key: `${index}-${name.replace(/\s+/g, "-")}` }));
}

function BackLink({ to, children }: { to: string; children: string }) {
  return <Link className="bfr-back-link" to={to}><ArrowLeft size={16} />{children}</Link>;
}
