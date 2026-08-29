import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  Search,
  Settings2,
  UserRound,
} from "lucide-react";
import { type FormEvent, useState } from "react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, TextField } from "../../design-system";
import { compactId, shortDateTime } from "../../lib/format";
import { ErrorState, StatusText } from "../../presentation/shared";
import { WorkspaceReferencePage } from "../../presentation/beanflow-refresh";

type Compensation = components["schemas"]["CompensationSummary"];

export function OpsDashboardPage() {
  return (
    <WorkspaceReferencePage title="운영 대시보드" description="플랫폼 운영과 관련된 주요 업무를 한눈에 확인하고 관리할 수 있습니다.">
      <section className="bfr-operations-shortcuts" aria-label="주요 업무">
        <Link className="surface-card shortcut-card" to="/ops/orders"><Search /><div><strong>보상 조회</strong><span>감사 사유와 주문 ID로 상세 상태 확인</span></div><ArrowRight /></Link>
        <Link className="surface-card shortcut-card" to="/ops/merchant-accounts"><UserRound /><div><strong>점주 계정 관리</strong><span>정확한 계정을 조회하고 잠금·비밀번호 처리</span></div><ArrowRight /></Link>
        <Link className="surface-card shortcut-card" to="/ops/policies"><Settings2 /><div><strong>정책 관리</strong><span>포인트·복원·브랜드·검색 정책 확인</span></div><ArrowRight /></Link>
      </section>
      <p className="bfr-workspace-support-copy">집계 API가 제공되지 않는 업무 수치는 추측해 표시하지 않습니다.</p>
    </WorkspaceReferencePage>
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
  return <WorkspaceReferencePage title="보상 조회" description="정확한 주문 ID와 업무상 접근 사유로 보상 상태를 조회합니다.">
    <form className="lookup-bar lookup-bar-two" onSubmit={(event) => void lookup(event)}><div><Search size={18} /><TextField label="주문 ID" id="ops-order-id" value={orderId} onValueChange={setOrderId} placeholder="UUID 입력" /><TextField label="접근 사유" value={accessReason} onValueChange={setAccessReason} placeholder="감사 접근 사유" /><Button type="submit" disabled={loading || !orderId.trim() || !accessReason.trim()}>조회</Button></div></form>
    {loading ? <LoadingState label="보상 상태를 조회하는 중" /> : null}{error ? <ErrorState error={error} /> : null}
    {!result && !loading && !error ? <EmptyState title="감사 조회 대기" description="주문 ID와 업무상 접근 사유가 모두 필요합니다." /> : null}
    {result ? <CompensationResult result={result} /> : null}
  </WorkspaceReferencePage>;
}

export function CompensationResult({ result }: { result: Compensation }) {
  return <section className="surface-card compensation-card"><div className="panel-heading"><div><span className="context-label">CASE {compactId(result.caseId)}</span><h2>{result.trigger === "STORE_REJECTION" ? "매장 거절 보상" : "고객 취소 보상"}</h2></div><StatusText state={result.state} /></div><div className="compensation-steps">{result.steps.map((step) => <article key={step.type}><span>{step.state === "SUCCEEDED" || step.state === "NOT_REQUIRED" ? <CheckCircle2 size={19} /> : <AlertTriangle size={19} />}</span><div><strong>{step.type}</strong><small>{step.state} · 시도 {step.attemptCount}회{step.lastErrorCode ? ` · ${step.lastErrorCode}` : ""}</small></div></article>)}</div><p className="form-footnote">최종 갱신 {shortDateTime.format(new Date(result.updatedAt))} · 주문의 종료 상태와 보상 성공은 독립적입니다.</p></section>;
}
