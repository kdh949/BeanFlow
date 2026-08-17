import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  RotateCcw,
  Search,
} from "lucide-react";
import { type FormEvent, type ReactNode, useState } from "react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { EmptyState, ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { compactId, shortDateTime } from "../../lib/format";
import { Button } from "../../design-system";

type Compensation = components["schemas"]["CompensationSummary"];

function OverviewCard({ icon, label, value, hint }: { icon: ReactNode; label: string; value: string; hint: string }) {
  return <article className="metric-card"><span>{icon}</span><small>{label}</small><strong>{value}</strong><p>{hint}</p></article>;
}

export function OpsDashboardPage() {
  return (
    <div className="console-page">
      <PageTitle eyebrow="OPERATIONS" title="플랫폼 운영 현황" description="금전 조정과 보상 복구를 서버 소유 상태로 확인합니다." />
      <section className="metric-grid">
        <OverviewCard icon={<RotateCcw />} label="환불" value="매장 콘솔" hint="주문번호와 품목으로 실행하고 운영 화면은 조회만 담당" />
        <OverviewCard icon={<AlertTriangle />} label="미확정 결과" value="UNKNOWN 유지" hint="성공이나 실패로 추정하지 않음" />
        <OverviewCard icon={<CheckCircle2 />} label="감사 접근" value="사유 필수" hint="운영자 보상 조회 기록" />
      </section>
      <section className="console-shortcuts">
        <Link className="surface-card shortcut-card" to="/ops/orders"><Search /><div><strong>보상 조회</strong><span>감사 사유와 주문 번호로 상세 상태 확인</span></div><ArrowRight /></Link>
      </section>
    </div>
  );
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
      const response = await operationsApi.GET("/operations/orders/{orderId}/compensation", { params: { path: { orderId: orderId.trim() }, header: { "X-Access-Reason": accessReason.trim() } } });
      setResult(unwrap(response).compensation);
    } catch (failure) { setError(failure); } finally { setLoading(false); }
  }
  return <div className="console-page">
    <PageTitle eyebrow="COMPENSATION" title="주문 보상 조회" description="권한 있는 운영자가 감사 사유를 남긴 뒤 상세 복구 단계를 확인합니다." />
    <form className="lookup-bar lookup-bar-two" onSubmit={(event) => void lookup(event)}><label htmlFor="ops-order-id">주문 번호</label><div><Search size={18} /><input id="ops-order-id" value={orderId} onChange={(event) => setOrderId(event.target.value)} placeholder="UUID 주문 번호" /><input aria-label="접근 사유" value={accessReason} onChange={(event) => setAccessReason(event.target.value)} placeholder="감사 접근 사유" /><Button type="submit" disabled={loading || !orderId.trim() || !accessReason.trim()}>조회</Button></div></form>
    {loading ? <LoadingState label="보상 상태를 조회하는 중" /> : null}{error ? <ErrorState error={error} /> : null}
    {!result && !loading && !error ? <EmptyState title="감사 조회 대기" description="주문 번호와 업무상 접근 사유가 모두 필요합니다." /> : null}
    {result ? <CompensationResult result={result} /> : null}
  </div>;
}

export function CompensationResult({ result }: { result: Compensation }) {
  return <section className="surface-card compensation-card"><div className="panel-heading"><div><span className="eyebrow">CASE {compactId(result.caseId)}</span><h2>{result.trigger === "STORE_REJECTION" ? "매장 거절 보상" : "고객 취소 보상"}</h2></div><StatusBadge state={result.state} /></div><div className="compensation-steps">{result.steps.map((step) => <article key={step.type}><span>{step.state === "SUCCEEDED" || step.state === "NOT_REQUIRED" ? <CheckCircle2 size={19} /> : <AlertTriangle size={19} />}</span><div><strong>{step.type}</strong><small>{step.state} · 시도 {step.attemptCount}회{step.lastErrorCode ? ` · ${step.lastErrorCode}` : ""}</small></div></article>)}</div><p className="form-footnote">최종 갱신 {shortDateTime.format(new Date(result.updatedAt))} · 주문의 종료 상태와 보상 성공은 독립적입니다.</p></section>;
}
