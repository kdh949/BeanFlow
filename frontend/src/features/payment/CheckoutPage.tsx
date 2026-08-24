import { ArrowLeft, Check, Coffee, CreditCard, ShieldCheck, Timer, TrendingUp } from "lucide-react";
import { useCallback, useState } from "react";
import { Link, useLocation, useParams } from "react-router";
import type { components } from "../../api/schema";
import { idempotencyKey, unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { ErrorState, LoadingState } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { shortDateTime, won } from "../../lib/format";
import { requestTossStandardPayment } from "../../payment/toss";
import { useResource } from "../shared/useResource";
import { attemptStorage } from "./paymentAttempt";
import { Button } from "../../design-system";

type Order = components["schemas"]["Order"];
type ReorderPriceComparison = components["schemas"]["ReorderPriceComparison"];

export function CheckoutPage() {
  const { orderId = "" } = useParams();
  const location = useLocation();
  const routeState = location.state as { reorderPriceComparison?: ReorderPriceComparison } | null;
  const priceComparison = routeState?.reorderPriceComparison;
  const [failure, setFailure] = useState<unknown>(null);
  const [paying, setPaying] = useState(false);

  const load = useCallback(
    async () => unwrap(await customerApi.GET("/orders/{orderId}", { params: { path: { orderId } } })),
    [orderId],
  );
  const { state, reload } = useResource<Order>(load);

  async function pay(order: Order) {
    if (order.state !== "PENDING_PAYMENT") return;
    setPaying(true);
    setFailure(null);
    try {
      const attempt = unwrap(await customerApi.POST("/orders/{orderId}/payment-attempts", {
        params: {
          path: { orderId },
          header: {
            "Idempotency-Key": idempotencyKey(`payment-attempt.${orderId}`),
            ...(await customerCsrfHeader()),
          },
        },
      }));
      attemptStorage.save(attempt);
      const config = unwrap(await customerApi.GET("/payment-config"));
      await requestTossStandardPayment(config.clientKey, {
        customerKey: attempt.customerKey,
        method: attempt.method,
        amount: attempt.amount,
        orderId: attempt.providerOrderId,
        orderName: attempt.orderName,
        successUrl: attempt.successUrl,
        failUrl: attempt.failUrl,
      });
    } catch (error) {
      setFailure(error);
      setPaying(false);
    }
  }

  if (state.status === "loading") return <LoadingState label="주문서를 불러오는 중" />;
  if (state.status === "failed") return <ErrorState error={state.error} retry={reload} />;
  const order = state.value;

  return (
    <div className="customer-page checkout-page">
      <Link className="back-link" to={`/app/orders/${order.publicReference}`}><ArrowLeft size={17} /> 주문 보기</Link>
      <PageTitle eyebrow="CHECKOUT" title="주문을 확인해 주세요" description="금액과 픽업 주문을 확인한 뒤 Toss 결제창에서 카드 또는 간편결제를 선택합니다." />
      {priceComparison?.hasPriceChanges ? <ReorderPriceNotice comparison={priceComparison} /> : null}
      <section className="checkout-card surface-card">
        <div className="card-kicker"><Coffee size={18} /> 주문 메뉴</div>
        {order.lines.map((line) => (
          <div className="order-line" key={line.orderLineId}>
            <div><strong>{line.menuName}</strong><span>{line.optionNames.join(" · ") || "기본 옵션"} · {line.quantity}잔</span></div>
            <strong>{won.format(line.cashPaidKrw)}</strong>
          </div>
        ))}
        <dl className="price-list">
          <div><dt>상품 금액</dt><dd>{won.format(order.subtotalKrw)}</dd></div>
          {order.couponDiscountKrw > 0 ? <div><dt>쿠폰 할인</dt><dd>-{won.format(order.couponDiscountKrw)}</dd></div> : null}
          {order.pointsAppliedKrw > 0 ? <div><dt>포인트 사용</dt><dd>-{won.format(order.pointsAppliedKrw)}</dd></div> : null}
          <div className="price-total"><dt>결제할 금액</dt><dd>{won.format(order.payableKrw)}</dd></div>
        </dl>
      </section>
      <section className="payment-choice surface-card">
        <div className="card-kicker"><CreditCard size={18} /> 결제 수단</div>
        <div className="single-method"><span className="method-icon"><CreditCard size={22} /></span><div><strong>카드 · 간편결제</strong><span>Toss Payments 통합결제창에서 선택</span></div><Check size={18} /></div>
        <p><ShieldCheck size={15} /> BeanFlow는 카드 번호를 저장하거나 처리하지 않습니다.</p>
      </section>
      {order.reservationExpiresAt ? <div className="lease-note"><Timer size={16} /> {shortDateTime.format(new Date(order.reservationExpiresAt))}까지 결제를 완료해 주세요.</div> : null}
      {failure ? <ErrorState error={failure} /> : null}
      <Button size="xl" block loading={paying} disabled={order.state !== "PENDING_PAYMENT"} onClick={() => void pay(order)}>
        {paying ? "Toss 결제창을 여는 중" : `${won.format(order.payableKrw)} 결제하기`}
      </Button>
      <p className="checkout-legal">결제 버튼을 누르면 주문 내용과 결제 진행에 동의합니다.</p>
    </div>
  );
}

export function ReorderPriceNotice({ comparison }: { comparison: ReorderPriceComparison }) {
  return (
    <section className="surface-card reorder-price-notice" role="status" aria-label="재주문 가격 변경">
      <TrendingUp size={20} />
      <div>
        <strong>현재 가격으로 다시 계산했어요</strong>
        <span>
          이전 {won.format(comparison.sourceSubtotalKrw)} → 현재 {won.format(comparison.currentSubtotalKrw)}
          {comparison.subtotalDifferenceKrw > 0 ? ` · ${won.format(comparison.subtotalDifferenceKrw)} 인상` : ` · ${won.format(Math.abs(comparison.subtotalDifferenceKrw))} 인하`}
        </span>
        <small>가격이 달라진 주문 항목 {comparison.items.length}개가 현재 주문 금액에 반영됐습니다.</small>
      </div>
    </section>
  );
}
