import { RotateCcw } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { useNavigate } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { EmptyState, ErrorState, LoadingState } from "../../components/Ui";
import { useResource } from "../shared/useResource";
import { reorderFailure } from "./reorderFailures";
import { Button } from "../../design-system";
import { couponSelection, useCouponSelection } from "../customer/couponSelection";

type PickupSlot = components["schemas"]["PickupSlot"];
type Order = components["schemas"]["Order"];

/**
 * Reorders the owned order by its public reference. The server rebuilds the
 * order from the current catalogue, so this screen never sends a source ID or a
 * remembered price.
 */
export function ReorderPanel({ orderReference, storeId, storeName }: {
  orderReference: string;
  storeId: string;
  storeName: string;
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [selectedSlot, setSelectedSlot] = useState("");
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const intent = useRef(new SubmissionIntent());
  const selectedCoupon = useCouponSelection(storeId);

  const loadSlots = useCallback(
    async () => unwrap(await customerApi.GET("/stores/{storeId}/pickup-slots", { params: { path: { storeId } } })).items,
    [storeId],
  );
  const slots = useResource<PickupSlot[]>(loadSlots);

  async function reorder() {
    if (!selectedSlot) return;
    const body = {
      pickupSlotId: selectedSlot,
      pointsToUseKrw: 0,
      ...(selectedCoupon ? { couponIssuanceId: selectedCoupon.couponIssuanceId } : {}),
    };
    setSubmitting(true);
    setFailure(null);
    try {
      const result = await customerApi.POST("/me/orders/{orderReference}/reorders", {
        params: {
          path: { orderReference },
          header: {
            "Idempotency-Key": intent.current.keyFor(JSON.stringify({ orderReference, ...body })),
            ...(await customerCsrfHeader()),
          },
        },
        body,
      });
      const created = unwrap(result);
      const order = created.order as Order;
      intent.current.complete();
      couponSelection.clear(storeId);
      navigate(
        order.payableKrw > 0 ? `/app/checkout/${order.orderId}` : `/app/orders/${order.publicReference}`,
        { state: { reorderPriceComparison: created.priceComparison } },
      );
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") intent.current.rotate();
      setFailure(error);
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) {
    return (
      <Button variant="secondary" block onClick={() => setOpen(true)}>
        <RotateCcw size={17} /> 같은 메뉴로 다시 주문
      </Button>
    );
  }

  const guidance = reorderFailure(failure);
  const availableSlots = slots.state.status === "ready" ? slots.state.value.filter((slot) => slot.remainingCapacity > 0) : [];

  return (
    <section className="surface-card reorder-panel" aria-label="다시 주문">
      <strong>{storeName}에서 같은 메뉴로 주문할까요?</strong>
      <p>메뉴와 옵션은 지금 판매 중인 구성으로 다시 확인해요. 금액도 현재 가격으로 계산됩니다.</p>
      {selectedCoupon ? <p className="inline-note">{selectedCoupon.label} 쿠폰도 주문할 때 다시 확인합니다.</p> : null}

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
              onClick={() => {
                if (selectedSlot !== slot.pickupSlotId) intent.current.rotate();
                setSelectedSlot(slot.pickupSlotId);
              }}
            >
              <strong>{new Date(slot.startsAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}</strong>
              <small>{slot.remainingCapacity}잔 가능</small>
            </button>
          ))}
        </div>
      ) : null}

      {guidance ? (
        <div className="reorder-conflict" role="alert">
          <strong>{guidance.title}</strong>
          <p>{guidance.description}</p>
          {guidance.items.length ? (
            <ul>
              {guidance.items.map((item) => <li key={`${item.lineSequence}-${item.reason}`}>{item.label}</li>)}
            </ul>
          ) : null}
        </div>
      ) : failure ? <ErrorState error={failure} /> : null}

      <div className="cancel-actions">
        <Button variant="ghost" onClick={() => setOpen(false)}>닫기</Button>
        <Button loading={submitting} disabled={!selectedSlot} onClick={() => void reorder()}>
          {submitting ? "주문을 만드는 중" : "이 시간으로 주문"}
        </Button>
      </div>
    </section>
  );
}
