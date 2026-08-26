import { ArrowLeft, Minus, Plus, Trash2 } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { EmptyState, ErrorState, LoadingState } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { won } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { Button, ButtonLink } from "../../design-system";
import { couponSelection, useCouponSelection } from "../customer/couponSelection";
import { useStore } from "../discovery/useStore";
import { type CartLine, cart, useCart } from "./cart";
import { orderConflictGuidance, shouldRotateIdempotencyKey } from "./orderConflicts";
import { nextPickupLabel, operatingStatusLabel } from "../discovery/storeDisplay";

type PickupSlot = components["schemas"]["PickupSlot"];
type Order = components["schemas"]["Order"];
type OrderQuote = components["schemas"]["OrderQuote"];

type QuoteState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; quote: OrderQuote }
  | { status: "failed"; error: unknown }
  | { status: "stale"; quote: OrderQuote };

export function CartPage() {
  const state = useCart();

  if (state.status === "corrupt") {
    return (
      <div className="customer-page">
        <PageTitle eyebrow="CART" title="장바구니" />
        <div className="surface-card cart-conflict" role="alert">
          <strong>장바구니 정보를 읽지 못했어요</strong>
          <p>이 기기에 저장된 장바구니가 손상됐습니다. 비운 뒤 다시 담아 주세요.</p>
          <div>
            <Button onClick={() => cart.clear()}>장바구니 비우기</Button>
          </div>
        </div>
      </div>
    );
  }

  if (state.status === "empty") {
    return (
      <div className="customer-page">
        <PageTitle eyebrow="CART" title="장바구니" />
        <EmptyState
          title="담은 메뉴가 없어요"
          description="매장을 골라 메뉴를 담으면 여기에서 픽업 시간을 정할 수 있어요."
          action={<ButtonLink to="/app/stores">매장 찾기</ButtonLink>}
        />
      </div>
    );
  }

  return <CartContents storeId={state.cart.storeId} savedStoreName={state.cart.storeName} lines={state.cart.lines} />;
}

function CartContents({ storeId, savedStoreName, lines }: {
  storeId: string;
  savedStoreName: string;
  lines: CartLine[];
}) {
  const navigate = useNavigate();
  const [selectedSlot, setSelectedSlot] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const [quoteState, setQuoteState] = useState<QuoteState>({ status: "idle" });
  const [quoteReload, setQuoteReload] = useState(0);
  const quoteRequest = useRef(0);
  const orderIntent = useRef(new SubmissionIntent());

  // The saved name is what this browser recorded when the menu was added. The
  // server owns the current one, so it wins whenever the read succeeds; a failed
  // read leaves the saved name in place rather than blocking the order.
  const store = useStore(storeId);
  const storeName = store.state.status === "ready" ? store.state.value.name : savedStoreName;
  const storeAcceptsOrders = store.state.status !== "ready" || store.state.value.orderingAvailable;
  const selectedCoupon = useCouponSelection(storeId);

  const loadSlots = useCallback(
    async () => unwrap(await customerApi.GET("/stores/{storeId}/pickup-slots", { params: { path: { storeId } } })).items,
    [storeId],
  );
  const slots = useResource<PickupSlot[]>(loadSlots);

  useEffect(() => {
    orderIntent.current.rotate();
    setFailure(null);
    const requestId = ++quoteRequest.current;
    if (!selectedSlot || !storeAcceptsOrders) {
      setQuoteState({ status: "idle" });
      return;
    }
    setQuoteState({ status: "loading" });
    const timer = window.setTimeout(() => {
      const body = editableOrderInput(storeId, selectedSlot, lines, selectedCoupon?.couponIssuanceId);
      void (async () => {
        try {
          const quote = unwrap(
            await customerApi.POST("/me/order-quotes", {
              params: { header: await customerCsrfHeader() },
              body,
            }),
          ) as OrderQuote;
          if (quoteRequest.current === requestId) setQuoteState({ status: "ready", quote });
        } catch (error) {
          if (quoteRequest.current === requestId) setQuoteState({ status: "failed", error });
        }
      })();
    }, 250);
    return () => window.clearTimeout(timer);
  }, [storeId, selectedSlot, lines, selectedCoupon?.couponIssuanceId, storeAcceptsOrders, quoteReload]);

  async function createOrder() {
    if (!selectedSlot || !storeAcceptsOrders || quoteState.status !== "ready") return;
    const body = {
      ...editableOrderInput(storeId, selectedSlot, lines, selectedCoupon?.couponIssuanceId),
      expectedQuoteFingerprint: quoteState.quote.quoteFingerprint,
    };
    setSubmitting(true);
    setFailure(null);
    try {
      const result = await customerApi.POST("/orders", {
        params: {
          header: {
            "Idempotency-Key": orderIntent.current.keyFor(JSON.stringify(body)),
            ...(await customerCsrfHeader()),
          },
        },
        body,
      });
      const order = unwrap(result).order as Order;
      orderIntent.current.complete();
      cart.clear();
      couponSelection.clear(storeId);
      navigate(order.payableKrw > 0 ? `/app/checkout/${order.orderId}` : `/app/orders/${order.publicReference}`);
    } catch (error) {
      const currentQuote = staleQuote(error);
      if (currentQuote) {
        setQuoteState({ status: "stale", quote: currentQuote });
      } else {
        if (shouldRotateIdempotencyKey(error)) orderIntent.current.rotate();
        setFailure(error);
      }
    } finally {
      setSubmitting(false);
    }
  }

  const guidance = orderConflictGuidance(failure);
  const visibleQuote = quoteState.status === "ready" || quoteState.status === "stale" ? quoteState.quote : null;
  const availableSlots = slots.state.status === "ready" ? slots.state.value.filter((slot) => slot.remainingCapacity > 0) : [];

  return (
    <div className="customer-page cart-page">
      <Link className="back-link" to={`/app/stores/${storeId}`}><ArrowLeft size={17} /> 메뉴 더 담기</Link>
      <PageTitle eyebrow="CART" title="장바구니" description={`${storeName}에서 픽업합니다.`} />

      {store.state.status === "loading" ? (
        <p className="inline-note" role="status">매장 주문 상태를 확인하고 있어요.</p>
      ) : null}
      {store.state.status === "failed" ? (
        <p className="inline-note" role="status">매장 안내를 불러오지 못했어요. 최종 주문 요청에서 주문 가능 여부를 다시 확인합니다.</p>
      ) : null}
      {store.state.status === "ready" ? (
        <section className="surface-card cart-store-status" aria-label="매장 주문 상태">
          <dl className="store-profile-summary">
            <div><dt>주문</dt><dd>{store.state.value.orderingAvailable ? "주문 가능" : "주문 불가"}</dd></div>
            <div><dt>운영시간</dt><dd>{operatingStatusLabel(store.state.value.customerDisplay.operatingStatus)}</dd></div>
            <div><dt>픽업</dt><dd>{nextPickupLabel(store.state.value.nextPickupWindow)}</dd></div>
          </dl>
          <p>{store.state.value.customerDisplay.addressLine ?? "주소 정보 없음"}</p>
          {!store.state.value.orderingAvailable ? (
            <p className="cart-store-warning" role="status">운영시간 상태와 관계없이 이 매장은 현재 주문을 받지 않아요.</p>
          ) : null}
        </section>
      ) : null}

      <section className="surface-card cart-lines" aria-label="담은 메뉴">
        {lines.map((line, index) => (
          <div className="cart-line" key={`${line.menuId}-${line.optionIds.join("-")}`}>
            <div>
              <strong>{line.display.menuName}</strong>
              <span>{line.display.optionNames.join(" · ") || "기본 옵션"}</span>
            </div>
            <span className="stepper">
              <button type="button" aria-label={`${line.display.menuName} 수량 줄이기`} onClick={() => cart.setQuantity(index, line.quantity - 1)}>
                {line.quantity === 1 ? <Trash2 size={15} /> : <Minus size={16} />}
              </button>
              <strong>{line.quantity}</strong>
              <button type="button" aria-label={`${line.display.menuName} 수량 늘리기`} onClick={() => cart.setQuantity(index, Math.min(20, line.quantity + 1))}>
                <Plus size={16} />
              </button>
            </span>
            <b>{visibleQuote ? won.format(visibleQuote.lines[index]?.lineTotalKrw ?? 0) : "서버 확인 전"}</b>
          </div>
        ))}
        {quoteState.status === "idle" ? (
          <p className="form-footnote" role="status">픽업 시간을 선택하면 서버가 현재 금액과 혜택을 확인해요.</p>
        ) : null}
        {quoteState.status === "loading" ? <LoadingState label="현재 주문 금액을 확인하는 중" /> : null}
        {quoteState.status === "failed" ? (
          <ErrorState error={quoteState.error} retry={() => setQuoteReload((value) => value + 1)} />
        ) : null}
        {visibleQuote ? <QuotePricing quote={visibleQuote} /> : null}
        {quoteState.status === "ready" ? (
          <p className="form-footnote">예약 전 견적입니다. 주문을 누르면 서버가 같은 조건인지 다시 확인해요.</p>
        ) : null}
      </section>

      <section className="cart-slots">
        <h2>픽업 시간</h2>
        {slots.state.status === "loading" ? <LoadingState label="픽업 시간을 불러오는 중" /> : null}
        {slots.state.status === "failed" ? <ErrorState error={slots.state.error} retry={slots.reload} /> : null}
        {slots.state.status === "ready" && availableSlots.length === 0 ? (
          <EmptyState title="지금 고를 수 있는 픽업 시간이 없어요" description="잠시 뒤 다시 확인해 주세요." />
        ) : null}
        {availableSlots.length ? (
          <div className="slot-grid">
            {availableSlots.map((slot) => (
              <button
                key={slot.pickupSlotId}
                type="button"
                aria-pressed={selectedSlot === slot.pickupSlotId}
                className={selectedSlot === slot.pickupSlotId ? "is-selected" : ""}
                disabled={!storeAcceptsOrders}
                onClick={() => {
                  if (selectedSlot !== slot.pickupSlotId) orderIntent.current.rotate();
                  setSelectedSlot(slot.pickupSlotId);
                }}
              >
                <strong>{new Date(slot.startsAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}</strong>
                <small>{slot.remainingCapacity}잔 가능</small>
              </button>
            ))}
          </div>
        ) : null}
      </section>

      <section className="surface-card cart-benefit" aria-label="선택한 쿠폰">
        <div>
          <span>쿠폰</span>
          <strong>{selectedCoupon ? selectedCoupon.label : "선택하지 않음"}</strong>
        </div>
        {selectedCoupon ? (
          <Button variant="ghost" onClick={() => couponSelection.clear(storeId)}>선택 해제</Button>
        ) : (
          <ButtonLink variant="ghost" to={`/app/coupons?storeId=${encodeURIComponent(storeId)}`}>쿠폰 보기</ButtonLink>
        )}
      </section>

      {guidance ? (
        <div className="surface-card cart-conflict" role="alert">
          <strong>{guidance.title}</strong>
          <p>{guidance.description}</p>
          <div>
            {guidance.recovery === "recheck-slots" ? (
              <Button onClick={() => { setSelectedSlot(""); slots.reload(); }}>픽업 시간 다시 보기</Button>
            ) : null}
            {guidance.recovery === "recheck-menu" ? (
              <ButtonLink to={`/app/stores/${storeId}`}>메뉴 다시 확인하기</ButtonLink>
            ) : null}
            {guidance.recovery === "wait" || guidance.recovery === "contact" ? (
              <ButtonLink to="/app/orders">주문 내역 보기</ButtonLink>
            ) : null}
          </div>
        </div>
      ) : failure ? <ErrorState error={failure} /> : null}

      {quoteState.status === "stale" ? (
        <div className="surface-card cart-conflict" role="alert">
          <strong>주문 금액과 조건이 변경됐어요</strong>
          <p>아래 서버 견적을 확인한 뒤 새 주문으로 다시 제출해 주세요. 이전 요청은 같은 결과만 재생합니다.</p>
          <div>
            <Button
              onClick={() => {
                orderIntent.current.rotate();
                setFailure(null);
                setQuoteState({ status: "ready", quote: quoteState.quote });
              }}
            >
              변경 내용 확인
            </Button>
          </div>
        </div>
      ) : null}

      <Button
        size="xl"
        block
        loading={submitting}
        disabled={!selectedSlot || !storeAcceptsOrders || quoteState.status !== "ready"}
        onClick={() => void createOrder()}
      >
        {submitting
          ? "주문을 만드는 중"
          : quoteState.status === "ready"
            ? `${won.format(quoteState.quote.pricing.payableKrw)} 주문하기`
            : "견적 확인 후 주문하기"}
      </Button>
    </div>
  );
}

function editableOrderInput(
  storeId: string,
  pickupSlotId: string,
  lines: CartLine[],
  couponIssuanceId?: string,
) {
  return {
    storeId,
    pickupSlotId,
    lines: lines.map((line) => ({ menuId: line.menuId, optionIds: line.optionIds, quantity: line.quantity })),
    pointsToUseKrw: 0,
    ...(couponIssuanceId ? { couponIssuanceId } : {}),
  };
}

function staleQuote(error: unknown): OrderQuote | null {
  if (!(error instanceof ApiRequestError) || error.code !== "ORDER_QUOTE_STALE") return null;
  const quote = error.currentQuote;
  if (!quote || typeof quote !== "object") return null;
  const candidate = quote as Partial<OrderQuote>;
  return typeof candidate.quoteFingerprint === "string" && /^[0-9a-f]{64}$/.test(candidate.quoteFingerprint) &&
    candidate.pricing !== undefined && Array.isArray(candidate.lines)
    ? candidate as OrderQuote
    : null;
}

function QuotePricing({ quote }: { quote: OrderQuote }) {
  return (
    <dl className="cart-quote-pricing" aria-label="서버 주문 견적">
      <div><dt>상품 금액</dt><dd>{won.format(quote.pricing.subtotalKrw)}</dd></div>
      <div><dt>쿠폰 할인</dt><dd>-{won.format(quote.pricing.couponDiscountKrw)}</dd></div>
      <div><dt>포인트 사용</dt><dd>-{won.format(quote.pricing.pointsAppliedKrw)}</dd></div>
      <div className="cart-total"><dt>결제 금액</dt><dd>{won.format(quote.pricing.payableKrw)}</dd></div>
    </dl>
  );
}
