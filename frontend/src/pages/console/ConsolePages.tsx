import {
  AlertTriangle,
  ArrowRight,
  BadgeCheck,
  CheckCircle2,
  FileClock,
  ListChecks,
  RotateCcw,
  Search,
} from "lucide-react";
import { type FormEvent, type ReactNode, useState } from "react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState, PageHeading, TextField } from "../../design-system";
import { compactId, shortDateTime } from "../../lib/format";
import { ErrorState, StatusText } from "../../presentation/shared";

type Compensation = components["schemas"]["CompensationSummary"];

function OverviewCard({ icon, label, value, hint }: { icon: ReactNode; label: string; value: string; hint: string }) {
  return <article className="metric-card"><span>{icon}</span><small>{label}</small><strong>{value}</strong><p>{hint}</p></article>;
}

export type OpsDashboardSummary = {
  failureAttention: number;
  settlementMismatch: number;
  auditAccessToday: number;
  refundApprovals: number;
  campaignInProgress: number;
  payoutReady: number;
};

export function OpsDashboardPage({ scenario = "contract-pending", summary }: { scenario?: "contract-pending" | "ready"; summary?: OpsDashboardSummary }) {
  return (
    <div className="console-page">
      <PageHeading title="플랫폼 운영 현황" />
      {scenario === "contract-pending" || !summary ? <InlineNotice tone="danger" announce="assertive" title="운영 요약을 준비하고 있습니다" description="현재는 아래 업무별 화면에서 상태를 확인해 주세요." /> : <section className="metric-grid" aria-label="운영 상태 요약">
        <OverviewCard icon={<AlertTriangle />} label="확인할 실패" value={`${summary.failureAttention}건`} hint="결제·알림·정산" />
        <OverviewCard icon={<RotateCcw />} label="정산 금액 차이" value={`${summary.settlementMismatch}건`} hint="자동으로 바꾸지 않는 정산 차이" />
        <OverviewCard icon={<CheckCircle2 />} label="오늘 정보 조회" value={`${summary.auditAccessToday}건`} hint="조회 사유가 기록된 건" />
        <OverviewCard icon={<BadgeCheck />} label="승인할 환불" value={`${summary.refundApprovals}건`} hint="한도와 권한 확인 필요" />
        <OverviewCard icon={<FileClock />} label="진행 중인 캠페인" value={`${summary.campaignInProgress}개`} hint="예약과 진행 상태" />
        <OverviewCard icon={<ListChecks />} label="지급 파일 준비" value={`${summary.payoutReady}건`} hint="파일 생성은 지급 완료가 아닙니다" />
      </section>}
      <section className="console-shortcuts">
        <Link className="surface-card shortcut-card" to="/ops/recovery"><AlertTriangle /><div><strong>문제와 정산 확인</strong><span>실패 업무, 정산 차이와 감사 기록 보기</span></div><ArrowRight /></Link>
        <Link className="surface-card shortcut-card" to="/ops/control"><ListChecks /><div><strong>승인과 지급 준비</strong><span>환불 승인, 캠페인과 지급 파일 처리</span></div><ArrowRight /></Link>
        <Link className="surface-card shortcut-card" to="/ops/orders"><Search /><div><strong>보상 내역 찾기</strong><span>주문 번호로 보상 상태 확인</span></div><ArrowRight /></Link>
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
    <PageHeading title="주문 보상 조회" />
    <form className="lookup-bar lookup-bar-two" onSubmit={(event) => void lookup(event)}><div><Search size={18} /><TextField label="주문 ID" id="ops-order-id" value={orderId} onValueChange={setOrderId} placeholder="UUID 입력" /><TextField label="접근 사유" value={accessReason} onValueChange={setAccessReason} placeholder="감사 접근 사유" /><Button type="submit" disabled={loading || !orderId.trim() || !accessReason.trim()}>조회</Button></div></form>
    {loading ? <LoadingState label="보상 상태를 조회하는 중" /> : null}{error ? <ErrorState error={error} /> : null}
    {!result && !loading && !error ? <EmptyState title="감사 조회 대기" description="주문 ID와 업무상 접근 사유가 모두 필요합니다." /> : null}
    {result ? <CompensationResult result={result} /> : null}
  </div>;
}

export function CompensationResult({ result }: { result: Compensation }) {
  return <section className="surface-card compensation-card"><div className="panel-heading"><div><span className="context-label">CASE {compactId(result.caseId)}</span><h2>{result.trigger === "STORE_REJECTION" ? "매장 거절 보상" : "고객 취소 보상"}</h2></div><StatusText state={result.state} /></div><div className="compensation-steps">{result.steps.map((step) => <article key={step.type}><span>{step.state === "SUCCEEDED" || step.state === "NOT_REQUIRED" ? <CheckCircle2 size={19} /> : <AlertTriangle size={19} />}</span><div><strong>{step.type}</strong><small>{step.state} · 시도 {step.attemptCount}회{step.lastErrorCode ? ` · ${step.lastErrorCode}` : ""}</small></div></article>)}</div><p className="form-footnote">최종 갱신 {shortDateTime.format(new Date(result.updatedAt))} · 주문의 종료 상태와 보상 성공은 독립적입니다.</p></section>;
}
