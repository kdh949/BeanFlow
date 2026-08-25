import { ArrowLeft, Minus, Plus, Trash2 } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { Link, useNavigate } from "react-router";
import type { components } from "../../api/schema";
import { SubmissionIntent, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { EmptyState, ErrorState, LoadingState } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { won } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { Button, ButtonLink } from "../../design-system";
import { couponSelection, useCouponSelection } from "../customer/couponSelection";
import { useStore } from "../discovery/useStore";
import { type CartLine, cart, cartDisplayTotalKrw, useCart } from "./cart";
import { orderConflictGuidance, shouldRotateIdempotencyKey } from "./orderConflicts";
import { nextPickupLabel, operatingStatusLabel } from "../discovery/storeDisplay";

type PickupSlot = components["schemas"]["PickupSlot"];
type Order = components["schemas"]["Order"];

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

  return <CartContents storeId={state.cart.storeId} savedStoreName={state.cart.storeName} lines={state.cart.lines} total={cartDisplayTotalKrw(state.cart)} />;
}

function CartContents({ storeId, savedStoreName, lines, total }: {
  storeId: string;
  savedStoreName: string;
  lines: CartLine[];
  total: number;
}) {
  const navigate = useNavigate();
  const [selectedSlot, setSelectedSlot] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
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

  async function createOrder() {
    if (!selectedSlot || !storeAcceptsOrders) return;
    const body = {
      storeId,
      pickupSlotId: selectedSlot,
      lines: lines.map((line) => ({ menuId: line.menuId, optionIds: line.optionIds, quantity: line.quantity })),
      pointsToUseKrw: 0,
      ...(selectedCoupon ? { couponIssuanceId: selectedCoupon.couponIssuanceId } : {}),
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
      if (shouldRotateIdempotencyKey(error)) orderIntent.current.rotate();
      setFailure(error);
    } finally {
      setSubmitting(false);
    }
  }

  const guidance = orderConflictGuidance(failure);
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
            <b>{won.format(line.display.unitPriceKrw * line.quantity)}</b>
          </div>
        ))}
        <div className="cart-total">
          <span>예상 금액</span>
          <strong>{won.format(total)}</strong>
        </div>
        <p className="form-footnote">최종 금액과 재고, 픽업 가능 여부는 주문할 때 매장 기준으로 다시 확인해요.</p>
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

      <Button
        size="xl"
        block
        loading={submitting}
        disabled={!selectedSlot || !storeAcceptsOrders}
        onClick={() => void createOrder()}
      >
        {submitting ? "주문을 만드는 중" : `${won.format(total)} 주문하기`}
      </Button>
    </div>
  );
}
