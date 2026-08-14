import {
  ArrowLeft,
  ArrowRight,
  Check,
  Coffee,
  CreditCard,
  LocateFixed,
  MapPin,
  Minus,
  Plus,
  RefreshCw,
  ShieldCheck,
  Timer,
  XCircle,
} from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Link, Navigate, useNavigate, useParams, useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { api, ApiRequestError, SubmissionIntent, idempotencyKey, unwrap } from "../../api/client";
import { EmptyState, ErrorState, LoadingState, StatusBadge, SuccessMark } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { compactId, shortDateTime, won } from "../../lib/format";
import { requestTossStandardPayment } from "../../payment/toss";

type NearbyStore = components["schemas"]["NearbyStore"];
type Menu = components["schemas"]["Menu"];
type PickupSlot = components["schemas"]["PickupSlot"];
type Order = components["schemas"]["Order"];
type Payment = components["schemas"]["PaymentConfirmation"];
type Attempt = components["schemas"]["OneTimePaymentAttempt"];

const attemptStorage = {
  save(attempt: Attempt) {
    sessionStorage.setItem(`beanflow.payment-attempt.${attempt.paymentId}`, JSON.stringify(attempt));
  },
  get(paymentId: string): Attempt | null {
    const value = sessionStorage.getItem(`beanflow.payment-attempt.${paymentId}`);
    if (!value) return null;
    try {
      return JSON.parse(value) as Attempt;
    } catch {
      return null;
    }
  },
};

type PaymentSuccessLocation = Pick<Location, "pathname" | "search" | "hash">;
type PaymentSuccessHistory = Pick<History, "state" | "replaceState">;

export function clearPaymentSuccessQuery(
  currentLocation: PaymentSuccessLocation = window.location,
  currentHistory: PaymentSuccessHistory = window.history,
) {
  if (!currentLocation.search) return;
  currentHistory.replaceState(
    currentHistory.state,
    "",
    `${currentLocation.pathname}${currentLocation.hash}`,
  );
}

export function CustomerHomePage() {
  const [searchParams] = useSearchParams();
  const initialLocation = useMemo(() => {
    const latitudeValue = searchParams.get("lat");
    const longitudeValue = searchParams.get("lng");
    if (latitudeValue === null || longitudeValue === null) return null;
    const latitude = Number(latitudeValue);
    const longitude = Number(longitudeValue);
    return Number.isFinite(latitude) && Number.isFinite(longitude) ? { latitude, longitude } : null;
  }, [searchParams]);
  const [location, setLocation] = useState(initialLocation);
  const [stores, setStores] = useState<NearbyStore[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [locating, setLocating] = useState(false);

  const load = useCallback(async (coordinates: { latitude: number; longitude: number }) => {
    setError(null);
    setStores(null);
    try {
      const result = await api.GET("/stores/nearby", {
        params: { query: { ...coordinates, radiusMeters: 10_000, limit: 20 } },
      });
      setStores(unwrap(result).items);
    } catch (failure) {
      setError(failure);
    }
  }, []);

  useEffect(() => {
    if (location) void load(location);
  }, [load, location]);

  function locate() {
    setLocating(true);
    setError(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setLocating(false);
        setLocation({ latitude: position.coords.latitude, longitude: position.coords.longitude });
      },
      () => {
        setLocating(false);
        setError(new Error("현재 위치를 확인할 수 없습니다. 브라우저 위치 권한을 확인해 주세요."));
      },
      { enableHighAccuracy: false, timeout: 8_000 },
    );
  }

  return (
    <div className="customer-page home-page">
      <section className="home-hero">
        <span className="eyebrow">PICKUP, WITHOUT THE WAIT</span>
        <h1>좋은 커피는<br />기다리지 않아도 돼요.</h1>
        <p>가까운 매장을 찾고, 도착 시간에 맞춰 픽업하세요.</p>
        <button className="location-pill" type="button" onClick={locate} disabled={locating}>
          <LocateFixed size={17} /> {locating ? "위치 확인 중" : location ? "현재 위치 다시 찾기" : "현재 위치로 매장 찾기"}
        </button>
      </section>

      <section className="home-section">
        <div className="section-heading">
          <div><span className="eyebrow">NEARBY</span><h2>가까운 매장</h2></div>
          {location ? <span className="muted-label">반경 10km</span> : null}
        </div>
        {!location && !error ? (
          <EmptyState title="위치를 알려주세요" description="정확한 좌표는 검색에만 사용하고 저장하지 않습니다." />
        ) : null}
        {location && stores === null && !error ? <LoadingState label="가까운 매장을 찾는 중" /> : null}
        {error ? <ErrorState error={error} retry={location ? () => void load(location) : locate} /> : null}
        {stores?.length === 0 ? <EmptyState title="가까운 매장이 없어요" description="검색 반경 안에 픽업 가능한 매장이 없습니다." /> : null}
        {stores?.map((store) => <StoreCard key={store.storeId} store={store} />)}
      </section>
    </div>
  );
}

function StoreCard({ store }: { store: NearbyStore }) {
  const available = store.open && store.pickupAvailable;
  return (
    <Link className={`store-card ${available ? "" : "is-closed"}`} to={`/app/stores/${store.storeId}`} aria-disabled={!available} tabIndex={available ? undefined : -1}>
      <span className="store-mark"><Coffee size={25} /></span>
      <span className="store-copy">
        <strong>{store.name}</strong>
        <span><MapPin size={14} /> {store.distanceMeters < 1_000 ? `${store.distanceMeters}m` : `${(store.distanceMeters / 1_000).toFixed(1)}km`}</span>
      </span>
      <span className={`availability ${available ? "is-open" : ""}`}>{available ? "주문 가능" : "준비 중"}</span>
      <ArrowRight size={18} />
    </Link>
  );
}

export function StoreCatalogPage() {
  const { storeId = "" } = useParams();
  const navigate = useNavigate();
  const [menus, setMenus] = useState<Menu[] | null>(null);
  const [slots, setSlots] = useState<PickupSlot[] | null>(null);
  const [selectedMenu, setSelectedMenu] = useState<Menu | null>(null);
  const [selectedSlot, setSelectedSlot] = useState<string>("");
  const [quantity, setQuantity] = useState(1);
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const orderSubmission = useRef(new SubmissionIntent());

  const load = useCallback(async () => {
    setError(null);
    try {
      const [menuResult, slotResult] = await Promise.all([
        api.GET("/stores/{storeId}/menus", { params: { path: { storeId } } }),
        api.GET("/stores/{storeId}/pickup-slots", { params: { path: { storeId } } }),
      ]);
      setMenus(unwrap(menuResult).items);
      setSlots(unwrap(slotResult).items);
    } catch (failure) {
      setError(failure);
    }
  }, [storeId]);

  useEffect(() => { void load(); }, [load]);

  async function createOrder() {
    if (!selectedMenu || !selectedSlot) return;
    const body = {
      storeId,
      pickupSlotId: selectedSlot,
      lines: [{ menuId: selectedMenu.menuId, optionIds: [], quantity }],
      pointsToUseKrw: 0,
    };
    const fingerprint = JSON.stringify(body);
    setSubmitting(true);
    setError(null);
    try {
      const result = await api.POST("/orders", {
        params: { header: { "Idempotency-Key": orderSubmission.current.keyFor(fingerprint) } },
        body,
      });
      const created = unwrap(result);
      const order = created.order as Order;
      orderSubmission.current.complete();
      navigate(order.payableKrw > 0 ? `/app/checkout/${order.orderId}` : `/app/orders/${order.publicReference}`);
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") {
        orderSubmission.current.rotate();
      }
      setError(failure);
    } finally {
      setSubmitting(false);
    }
  }

  if (!menus && !slots && !error) return <LoadingState label="메뉴와 픽업 시간을 준비하는 중" />;
  if (error && !menus) return <ErrorState error={error} retry={() => void load()} />;
  return (
    <div className="customer-page catalog-page">
      <Link className="back-link" to="/app"><ArrowLeft size={17} /> 매장 목록</Link>
      <PageTitle eyebrow="ORDER" title="메뉴를 골라주세요" description="현재 판매 가능한 메뉴와 픽업 시간만 보여드려요." />
      <section className="menu-list">
        {menus?.length ? menus.map((menu) => (
          <button
            key={menu.menuId}
            type="button"
            disabled={!menu.available}
            aria-pressed={selectedMenu?.menuId === menu.menuId}
            className={`menu-card ${selectedMenu?.menuId === menu.menuId ? "is-selected" : ""}`}
            onClick={() => {
              if (selectedMenu?.menuId !== menu.menuId) orderSubmission.current.rotate();
              setSelectedMenu(menu);
            }}
          >
            <span className="menu-icon"><Coffee size={25} /></span>
            <span><strong>{menu.name}</strong><small>{won.format(menu.basePriceKrw)}</small></span>
            {selectedMenu?.menuId === menu.menuId ? <Check size={19} /> : null}
          </button>
        )) : <EmptyState title="판매 중인 메뉴가 없어요" description="잠시 뒤 다시 확인해 주세요." />}
      </section>
      {selectedMenu ? (
        <section className="selection-panel surface-card">
          <div className="selection-row"><span>수량</span><span className="stepper">
            <button type="button" aria-label="수량 줄이기" onClick={() => setQuantity((value) => {
              const next = Math.max(1, value - 1);
              if (next !== value) orderSubmission.current.rotate();
              return next;
            })}><Minus size={16} /></button>
            <strong>{quantity}</strong>
            <button type="button" aria-label="수량 늘리기" onClick={() => setQuantity((value) => {
              const next = Math.min(20, value + 1);
              if (next !== value) orderSubmission.current.rotate();
              return next;
            })}><Plus size={16} /></button>
          </span></div>
          <div><span className="field-label">픽업 시간</span><div className="slot-grid">
            {slots?.map((slot) => (
              <button key={slot.pickupSlotId} type="button" aria-pressed={selectedSlot === slot.pickupSlotId} className={selectedSlot === slot.pickupSlotId ? "is-selected" : ""} onClick={() => {
                if (selectedSlot !== slot.pickupSlotId) orderSubmission.current.rotate();
                setSelectedSlot(slot.pickupSlotId);
              }}>
                <strong>{new Date(slot.startsAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}</strong>
                <small>{slot.remainingCapacity}잔 가능</small>
              </button>
            ))}
          </div></div>
          {error ? <ErrorState error={error} /> : null}
          <button className="button button-primary button-block button-xl" type="button" disabled={!selectedSlot || submitting} onClick={() => void createOrder()}>
            {submitting ? "주문을 만드는 중" : `${won.format(selectedMenu.basePriceKrw * quantity)} 주문하기`}
          </button>
        </section>
      ) : null}
    </div>
  );
}

export function CheckoutPage() {
  const { orderId = "" } = useParams();
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [paying, setPaying] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const result = await api.GET("/orders/{orderId}", { params: { path: { orderId } } });
      setOrder(unwrap(result));
    } catch (failure) {
      setError(failure);
    }
  }, [orderId]);
  useEffect(() => { void load(); }, [load]);

  async function pay() {
    if (!order || order.state !== "PENDING_PAYMENT") return;
    setPaying(true);
    setError(null);
    try {
      const attemptResult = await api.POST("/orders/{orderId}/payment-attempts", {
        params: {
          path: { orderId },
          header: { "Idempotency-Key": idempotencyKey(`payment-attempt.${orderId}`) },
        },
      });
      const attempt = unwrap(attemptResult);
      attemptStorage.save(attempt);
      const configResult = await api.GET("/payment-config");
      const config = unwrap(configResult);
      await requestTossStandardPayment(config.clientKey, {
        customerKey: attempt.customerKey,
        method: attempt.method,
        amount: attempt.amount,
        orderId: attempt.providerOrderId,
        orderName: attempt.orderName,
        successUrl: attempt.successUrl,
        failUrl: attempt.failUrl,
      });
    } catch (failure) {
      setError(failure);
      setPaying(false);
    }
  }

  if (!order && !error) return <LoadingState label="주문서를 불러오는 중" />;
  if (error && !order) return <ErrorState error={error} retry={() => void load()} />;
  if (!order) return null;
  return (
    <div className="customer-page checkout-page">
      <Link className="back-link" to={`/app/orders/${order.publicReference}`}><ArrowLeft size={17} /> 주문 보기</Link>
      <PageTitle eyebrow="CHECKOUT" title="주문을 확인해 주세요" description="금액과 픽업 주문을 확인한 뒤 Toss 결제창에서 카드 또는 간편결제를 선택합니다." />
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
      {error ? <ErrorState error={error} /> : null}
      <button className="button button-primary button-block button-xl" type="button" disabled={paying || order.state !== "PENDING_PAYMENT"} onClick={() => void pay()}>
        {paying ? "Toss 결제창을 여는 중" : `${won.format(order.payableKrw)} 결제하기`}
      </button>
      <p className="checkout-legal">결제 버튼을 누르면 주문 내용과 결제 진행에 동의합니다.</p>
    </div>
  );
}

export function PaymentSuccessPage() {
  const { paymentId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const [payment, setPayment] = useState<Payment | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [confirming, setConfirming] = useState(true);
  const paymentKey = searchParams.get("paymentKey") ?? "";
  const providerOrderId = searchParams.get("orderId") ?? "";
  const amount = Number(searchParams.get("amount"));
  const callbackQueryPresent = searchParams.has("paymentKey") || searchParams.has("orderId") || searchParams.has("amount");

  useLayoutEffect(() => clearPaymentSuccessQuery(), []);

  const confirm = useCallback(async () => {
    setConfirming(true);
    setError(null);
    try {
      if (!callbackQueryPresent) {
        const result = await api.GET("/payments/{paymentId}", { params: { path: { paymentId } } });
        setPayment(unwrap(result));
        return;
      }
      const attempt = attemptStorage.get(paymentId);
      if (attempt && (attempt.providerOrderId !== providerOrderId || attempt.amount.value !== amount)) {
        throw new ApiRequestError(400, "PAYMENT_CALLBACK_MISMATCH", "결제창에서 돌아온 정보가 주문과 일치하지 않습니다.");
      }
      if (!paymentKey || !providerOrderId || !Number.isSafeInteger(amount) || amount <= 0) {
        throw new ApiRequestError(400, "INVALID_PAYMENT_CALLBACK", "결제 결과 정보가 올바르지 않습니다.");
      }
      const result = await api.POST("/payments/{paymentId}/confirmations", {
        params: {
          path: { paymentId },
          header: { "Idempotency-Key": idempotencyKey(`payment-confirm.${paymentId}`) },
        },
        body: { paymentKey, orderId: providerOrderId, amount },
      });
      setPayment(unwrap(result));
    } catch (failure) {
      setError(failure);
    } finally {
      setConfirming(false);
    }
  }, [amount, callbackQueryPresent, paymentId, paymentKey, providerOrderId]);

  const refresh = useCallback(async () => {
    try {
      const result = await api.GET("/payments/{paymentId}", { params: { path: { paymentId } } });
      setPayment(unwrap(result));
      setError(null);
    } catch (failure) {
      setError(failure);
    }
  }, [paymentId]);

  useEffect(() => { void confirm(); }, [confirm]);
  useEffect(() => {
    if (!payment || !["READY", "APPROVING", "UNKNOWN", "RECONCILING"].includes(payment.approvalState)) return;
    const timer = window.setTimeout(() => void refresh(), 3_000);
    return () => window.clearTimeout(timer);
  }, [payment, refresh]);

  if (confirming && !payment) return <LoadingState label="결제를 안전하게 승인하는 중" />;
  if (error && !payment) return <div className="customer-page result-page"><ErrorState error={error} retry={() => void confirm()} /></div>;
  if (!payment) return null;
  const pending = payment.approvalState !== "APPROVED";
  return (
    <div className="customer-page result-page">
      {pending ? <span className="pending-mark"><RefreshCw className="spin" size={30} /></span> : <SuccessMark />}
      <span className="eyebrow">{pending ? "PAYMENT CHECK" : "ORDER CONFIRMED"}</span>
      <h1>{pending ? "결제 결과를 확인하고 있어요" : "결제가 완료됐어요"}</h1>
      <p>{pending ? "같은 결제를 다시 시도하지 않아도 됩니다. Provider 조회로 결과를 복구하고 있어요." : "매장에서 주문을 확인하면 픽업 준비 상태를 알려드릴게요."}</p>
      <StatusBadge state={payment.approvalState} />
      <div className="result-summary surface-card">
        <div><span>주문 번호</span><code>{compactId(payment.orderId)}</code></div>
        <div><span>승인 금액</span><strong>{payment.approvedAmountKrw == null ? "확인 중" : won.format(payment.approvedAmountKrw)}</strong></div>
        {payment.recovery ? <div><span>복구 상태</span><StatusBadge state={payment.recovery.state} /></div> : null}
      </div>
      {error ? <ErrorState error={error} retry={() => void refresh()} /> : null}
      <Link className="button button-primary button-block button-xl" to="/app/orders">주문 상태 보기</Link>
    </div>
  );
}

export function PaymentFailPage() {
  const { paymentId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const [payment, setPayment] = useState<Payment | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const code = publicFailureCode(searchParams.get("code") ?? "PAYMENT_AUTH_FAILED");
  const message = failureMessage(code);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await api.GET("/payments/{paymentId}", { params: { path: { paymentId } } });
      setPayment(unwrap(result));
      setError(null);
    } catch (failure) {
      setError(failure);
    } finally {
      setLoading(false);
    }
  }, [paymentId]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!payment || !["APPROVING", "UNKNOWN", "RECONCILING"].includes(payment.approvalState)) return;
    const timer = window.setTimeout(() => void load(), 3_000);
    return () => window.clearTimeout(timer);
  }, [load, payment]);

  if (loading && !payment) return <LoadingState label="결제 상태를 확인하는 중" />;
  if (error && !payment) return <div className="customer-page result-page"><ErrorState error={error} retry={() => void load()} /></div>;
  if (!payment) return null;
  if (payment.approvalState === "APPROVED") {
    return <Navigate replace to={`/app/payments/${paymentId}/success`} />;
  }
  if (["APPROVING", "UNKNOWN", "RECONCILING", "MANUAL_REVIEW"].includes(payment.approvalState)) {
    return (
      <div className="customer-page result-page">
        <span className="pending-mark"><RefreshCw className="spin" size={30} /></span>
        <span className="eyebrow">PAYMENT CHECK</span>
        <h1>결제 결과를 확인하고 있어요</h1>
        <p>같은 결제를 다시 시도하지 마세요. 서버가 현재 결제 상태를 확인하고 있습니다.</p>
        <StatusBadge state={payment.approvalState} />
        {error ? <ErrorState error={error} retry={() => void load()} /> : null}
        <Link className="button button-secondary button-block" to="/app/orders">주문 상태 보기</Link>
      </div>
    );
  }
  const retryable = payment.approvalState === "READY";
  return (
    <div className="customer-page result-page">
      <span className="failure-mark"><XCircle size={36} /></span>
      <span className="eyebrow">PAYMENT STOPPED</span>
      <h1>결제를 완료하지 못했어요</h1>
      <p>{message}</p>
      <code className="failure-code">{code}</code>
      <Link className="button button-primary button-block button-xl" to={retryable ? `/app/checkout/${payment.orderId}` : "/app/orders"}>
        {retryable ? "주문서로 돌아가기" : "주문 상태 보기"}
      </Link>
      <Link className="text-link" to="/app/help">도움이 필요해요</Link>
    </div>
  );
}

export function failureMessage(code: string) {
  const messages: Record<string, string> = {
    PAY_PROCESS_CANCELED: "결제를 취소했습니다. 주문서에서 다시 진행할 수 있어요.",
    PAY_PROCESS_ABORTED: "결제 인증이 중단됐습니다. 다른 카드나 간편결제로 다시 시도해 주세요.",
    REJECT_CARD_COMPANY: "카드사에서 승인을 거절했습니다. 카드사에 확인하거나 다른 수단을 이용해 주세요.",
  };
  return messages[code] ?? "결제 인증을 마치지 못했습니다. 주문서에서 안전하게 다시 시도할 수 있어요.";
}

export function publicFailureCode(code: string) {
  return ["PAY_PROCESS_CANCELED", "PAY_PROCESS_ABORTED", "REJECT_CARD_COMPANY"].includes(code)
    ? code
    : "PAYMENT_AUTH_FAILED";
}

export function CustomerHelpPage() {
  return <div className="customer-page"><PageTitle eyebrow="HELP" title="도움이 필요하신가요?" description="결제 결과가 확인 중이면 같은 결제를 반복하지 말고 주문 상태를 새로고침해 주세요." /><section className="surface-card help-card"><strong>결제·환불 문의</strong><p>문의할 때 화면의 문의 코드와 주문 번호를 알려주세요. 카드 번호나 인증 정보는 보내지 마세요.</p></section></div>;
}
