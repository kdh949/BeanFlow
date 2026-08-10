import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  ClipboardCheck,
  Coffee,
  PackageCheck,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldCheck,
} from "lucide-react";
import { type FormEvent, type ReactNode, useRef, useState } from "react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { api, ApiRequestError, SubmissionIntent, idempotencyKey, unwrap } from "../../api/client";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { compactId, shortDateTime, won } from "../../lib/format";

type StoreOrderResult = {
  order: components["schemas"]["Order"];
  compensationRecovery?: components["schemas"]["RuntimeStoreCompensationSummary"];
};
type Refund = components["schemas"]["Refund"];
type Compensation = components["schemas"]["CompensationSummary"];

const transitionOptions = [
  { value: "ACCEPTED", label: "주문 접수" },
  { value: "PREPARING", label: "제조 시작" },
  { value: "READY", label: "픽업 준비" },
  { value: "COMPLETED", label: "픽업 완료" },
  { value: "REJECTED", label: "주문 거절" },
] as const;

export function StoreDashboardPage() {
  return (
    <div className="console-page">
      <PageTitle eyebrow="STORE WORKSPACE" title="오늘의 주문 운영" description="주문 번호로 현재 상태를 확인하고, 허용된 다음 단계로 전환하세요." />
      <section className="metric-grid">
        <OverviewCard icon={<PackageCheck />} label="주문 상태" value="실시간 조회" hint="서버의 현재 상태만 표시" />
        <OverviewCard icon={<RefreshCw />} label="중복 요청" value="안전한 재실행" hint="명령별 멱등성 키 유지" />
        <OverviewCard icon={<ShieldCheck />} label="보상 처리" value="별도 추적" hint="주문 종료와 성공을 구분" />
      </section>
      <section className="surface-card console-intro">
        <div className="console-intro-icon"><Coffee size={28} /></div>
        <div><h2>주문 보드 시작하기</h2><p>현재 API는 목록 조회 대신 명시적인 주문 조회를 제공합니다. 주문 번호를 입력하면 상태 전환 도구가 열립니다.</p></div>
        <Link className="button button-primary" to="/store/lookup">주문 조회 <ArrowRight size={17} /></Link>
      </section>
    </div>
  );
}

function OverviewCard({ icon, label, value, hint }: { icon: ReactNode; label: string; value: string; hint: string }) {
  return <article className="metric-card"><span>{icon}</span><small>{label}</small><strong>{value}</strong><p>{hint}</p></article>;
}

export function StoreLookupPage() {
  const [orderId, setOrderId] = useState("");
  const [result, setResult] = useState<StoreOrderResult | null>(null);
  const [targetState, setTargetState] = useState<(typeof transitionOptions)[number]["value"]>("ACCEPTED");
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function lookup(event?: FormEvent) {
    event?.preventDefault();
    const normalized = orderId.trim();
    if (!normalized) return;
    setLoading(true);
    setError(null);
    try {
      const response = await api.GET("/store-orders/{orderId}", { params: { path: { orderId: normalized } } });
      setResult(unwrap(response));
    } catch (failure) {
      setError(failure);
      setResult(null);
    } finally {
      setLoading(false);
    }
  }

  async function transition() {
    if (!result) return;
    setLoading(true);
    setError(null);
    try {
      const response = await api.PATCH("/store-orders/{orderId}/status", {
        params: {
          path: { orderId: result.order.orderId },
          header: { "Idempotency-Key": idempotencyKey(`store-order.${result.order.orderId}.${targetState}`) },
        },
        body: { targetState, reason: reason.trim() || undefined },
      });
      const changed: StoreOrderResult = unwrap(response);
      setResult(changed);
      setReason("");
    } catch (failure) {
      setError(failure);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="console-page">
      <PageTitle eyebrow="ORDER DESK" title="매장 주문 조회" description="목록을 추정하지 않고 서버가 반환한 주문 한 건을 운영합니다." />
      <form className="lookup-bar" onSubmit={(event) => void lookup(event)}>
        <label htmlFor="store-order-id">주문 번호</label>
        <div><Search size={18} /><input id="store-order-id" value={orderId} onChange={(event) => setOrderId(event.target.value)} placeholder="UUID 주문 번호" autoComplete="off" /><button className="button button-primary" type="submit" disabled={loading || !orderId.trim()}>조회</button></div>
      </form>
      {loading && !result ? <LoadingState label="주문을 조회하는 중" /> : null}
      {error ? <ErrorState error={error} retry={() => void lookup()} /> : null}
      {!result && !loading && !error ? <EmptyState title="조회할 주문을 입력하세요" description="고객 화면이나 영수증의 주문 번호를 그대로 사용할 수 있습니다." /> : null}
      {result ? <StoreOrderPanel result={result} targetState={targetState} setTargetState={setTargetState} reason={reason} setReason={setReason} loading={loading} transition={transition} /> : null}
    </div>
  );
}

function StoreOrderPanel({ result, targetState, setTargetState, reason, setReason, loading, transition }: {
  result: StoreOrderResult;
  targetState: (typeof transitionOptions)[number]["value"];
  setTargetState: (value: (typeof transitionOptions)[number]["value"]) => void;
  reason: string;
  setReason: (value: string) => void;
  loading: boolean;
  transition: () => Promise<void>;
}) {
  const { order, compensationRecovery } = result;
  return <div className="console-detail-grid">
    <section className="surface-card order-panel">
      <div className="panel-heading"><div><span className="eyebrow">ORDER {compactId(order.orderId)}</span><h2>주문 상세</h2></div><StatusBadge state={order.state} /></div>
      <dl className="detail-list"><div><dt>주문 시각</dt><dd>{shortDateTime.format(new Date(order.createdAt))}</dd></div><div><dt>결제 금액</dt><dd>{won.format(order.payableKrw)}</dd></div><div><dt>매장</dt><dd><code>{compactId(order.storeId)}</code></dd></div></dl>
      <div className="line-items">{order.lines.map((line) => <div key={line.orderLineId}><span>{line.menuName} <small>× {line.quantity}</small></span><strong>{won.format(line.cashPaidKrw)}</strong></div>)}</div>
      {compensationRecovery ? <div className="recovery-note"><AlertTriangle size={18} /><div><strong>보상 처리 {compensationRecovery.state}</strong><span>{shortDateTime.format(new Date(compensationRecovery.updatedAt))} 기준이며 주문 종료와 별개로 추적됩니다.</span></div></div> : null}
    </section>
    <section className="surface-card action-panel">
      <span className="eyebrow">NEXT ACTION</span><h2>상태 전환</h2>
      <label htmlFor="target-state">변경할 상태</label><select id="target-state" value={targetState} onChange={(event) => setTargetState(event.target.value as typeof targetState)}>{transitionOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select>
      <label htmlFor="transition-reason">사유 {targetState === "REJECTED" ? "(필수)" : "(선택)"}</label><textarea id="transition-reason" value={reason} onChange={(event) => setReason(event.target.value)} placeholder="상태 변경 사유" />
      <button className="button button-primary button-block" type="button" disabled={loading || (targetState === "REJECTED" && !reason.trim())} onClick={() => void transition()}><ClipboardCheck size={17} /> {loading ? "처리 중" : "상태 변경"}</button>
      <p className="form-footnote">거절 응답이 202이면 보상 작업이 시작됐다는 뜻이며 완료를 의미하지 않습니다.</p>
    </section>
  </div>;
}

export function OpsDashboardPage() {
  return (
    <div className="console-page">
      <PageTitle eyebrow="OPERATIONS" title="플랫폼 운영 현황" description="금전 조정과 보상 복구를 서버 소유 상태로 확인합니다." />
      <section className="metric-grid">
        <OverviewCard icon={<RotateCcw />} label="환불" value="전액 · 부분" hint="Provider 결과를 명시적 상태로 표시" />
        <OverviewCard icon={<AlertTriangle />} label="미확정 결과" value="UNKNOWN 유지" hint="성공이나 실패로 추정하지 않음" />
        <OverviewCard icon={<CheckCircle2 />} label="감사 접근" value="사유 필수" hint="운영자 보상 조회 기록" />
      </section>
      <section className="console-shortcuts">
        <Link className="surface-card shortcut-card" to="/ops/refunds"><RotateCcw /><div><strong>환불 조정</strong><span>결제 번호와 상품 수량으로 전액·부분 환불</span></div><ArrowRight /></Link>
        <Link className="surface-card shortcut-card" to="/ops/orders"><Search /><div><strong>보상 조회</strong><span>감사 사유와 주문 번호로 상세 상태 확인</span></div><ArrowRight /></Link>
      </section>
    </div>
  );
}

export function OpsRefundPage() {
  const [paymentId, setPaymentId] = useState("");
  const [reason, setReason] = useState("");
  const [partial, setPartial] = useState(false);
  const [orderLineId, setOrderLineId] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [result, setResult] = useState<Refund | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const refundSubmission = useRef(new SubmissionIntent());

  async function submit(event: FormEvent) {
    event.preventDefault();
    const normalizedPaymentId = paymentId.trim();
    const body = { reason: reason.trim(), lineItems: partial ? [{ orderLineId: orderLineId.trim(), quantity }] : undefined };
    const fingerprint = JSON.stringify({ paymentId: normalizedPaymentId, ...body });
    setLoading(true); setError(null); setResult(null);
    try {
      const response = await api.POST("/payments/{paymentId}/refunds", {
        params: { path: { paymentId: normalizedPaymentId }, header: { "Idempotency-Key": refundSubmission.current.keyFor(fingerprint) } },
        body,
      });
      setResult(unwrap(response));
      refundSubmission.current.complete();
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED") {
        refundSubmission.current.rotate();
      }
      setError(failure);
    } finally { setLoading(false); }
  }

  return <div className="console-page narrow-console-page">
    <PageTitle eyebrow="PAYMENT ADJUSTMENT" title="환불 조정" description="전액 환불은 상품을 비워 두고, 부분 환불은 주문 상품과 수량을 명시합니다." />
    <form className="surface-card operation-form" onSubmit={(event) => void submit(event)}>
      <label htmlFor="payment-id">결제 번호</label><input id="payment-id" value={paymentId} onChange={(event) => setPaymentId(event.target.value)} placeholder="UUID 결제 번호" required />
      <label htmlFor="refund-reason">환불 사유</label><textarea id="refund-reason" value={reason} onChange={(event) => setReason(event.target.value)} placeholder="운영 사유를 입력하세요" required />
      <label className="switch-row"><input type="checkbox" checked={partial} onChange={(event) => setPartial(event.target.checked)} /><span><strong>부분 환불</strong><small>선택하지 않으면 남은 환불 가능 금액 전체를 요청합니다.</small></span></label>
      {partial ? <div className="inline-fields"><label>주문 상품 번호<input value={orderLineId} onChange={(event) => setOrderLineId(event.target.value)} required /></label><label>수량<input type="number" min={1} value={quantity} onChange={(event) => setQuantity(Number(event.target.value))} required /></label></div> : null}
      <button className="button button-primary button-block" disabled={loading || !paymentId.trim() || !reason.trim() || (partial && !orderLineId.trim())} type="submit"><RotateCcw size={17} /> {loading ? "환불 요청 중" : partial ? "부분 환불 요청" : "전액 환불 요청"}</button>
    </form>
    {error ? <ErrorState error={error} /> : null}
    {result ? <section className="surface-card result-card"><div><span className="eyebrow">REFUND {compactId(result.refundId)}</span><StatusBadge state={result.state} /></div><h2>{result.state === "SUCCEEDED" ? "현금 환불이 확인되었습니다" : "환불 결과를 확인 중입니다"}</h2><p>{result.state === "SUCCEEDED" ? `${won.format(result.cashRefundedKrw ?? result.cashRefundRequestedKrw)} 환불 완료` : "202 또는 미확정 상태는 성공이 아닙니다. 같은 키로 재요청하면 기존 작업을 조회합니다."}</p><dl className="detail-list"><div><dt>요청 금액</dt><dd>{won.format(result.cashRefundRequestedKrw)}</dd></div><div><dt>포인트 복구</dt><dd>{result.pointsRestorationState}</dd></div><div><dt>문의 코드</dt><dd><code>{result.correlationId}</code></dd></div></dl></section> : null}
  </div>;
}

export function OpsOrderPage() {
  const [orderId, setOrderId] = useState("");
  const [accessReason, setAccessReason] = useState("");
  const [result, setResult] = useState<Compensation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  async function lookup(event: FormEvent) {
    event.preventDefault(); setLoading(true); setError(null); setResult(null);
    try {
      const response = await api.GET("/operations/orders/{orderId}/compensation", { params: { path: { orderId: orderId.trim() }, header: { "X-Access-Reason": accessReason.trim() } } });
      setResult(unwrap(response).compensation);
    } catch (failure) { setError(failure); } finally { setLoading(false); }
  }
  return <div className="console-page">
    <PageTitle eyebrow="COMPENSATION" title="주문 보상 조회" description="권한 있는 운영자가 감사 사유를 남긴 뒤 상세 복구 단계를 확인합니다." />
    <form className="lookup-bar lookup-bar-two" onSubmit={(event) => void lookup(event)}><label htmlFor="ops-order-id">주문 번호</label><div><Search size={18} /><input id="ops-order-id" value={orderId} onChange={(event) => setOrderId(event.target.value)} placeholder="UUID 주문 번호" /><input aria-label="접근 사유" value={accessReason} onChange={(event) => setAccessReason(event.target.value)} placeholder="감사 접근 사유" /><button className="button button-primary" disabled={loading || !orderId.trim() || !accessReason.trim()}>조회</button></div></form>
    {loading ? <LoadingState label="보상 상태를 조회하는 중" /> : null}{error ? <ErrorState error={error} /> : null}
    {!result && !loading && !error ? <EmptyState title="감사 조회 대기" description="주문 번호와 업무상 접근 사유가 모두 필요합니다." /> : null}
    {result ? <section className="surface-card compensation-card"><div className="panel-heading"><div><span className="eyebrow">CASE {compactId(result.caseId)}</span><h2>{result.trigger === "STORE_REJECTION" ? "매장 거절 보상" : "고객 취소 보상"}</h2></div><StatusBadge state={result.state} /></div><div className="compensation-steps">{result.steps.map((step) => <article key={step.type}><span>{step.state === "SUCCEEDED" || step.state === "NOT_REQUIRED" ? <CheckCircle2 size={19} /> : <AlertTriangle size={19} />}</span><div><strong>{step.type}</strong><small>{step.state} · 시도 {step.attemptCount}회{step.lastErrorCode ? ` · ${step.lastErrorCode}` : ""}</small></div></article>)}</div><p className="form-footnote">최종 갱신 {shortDateTime.format(new Date(result.updatedAt))} · 주문의 종료 상태와 보상 성공은 독립적입니다.</p></section> : null}
  </div>;
}
