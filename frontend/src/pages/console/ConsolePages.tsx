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
import type { ReactNode } from "react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { InlineNotice, PageHeading } from "../../design-system";
import { compactId, shortDateTime } from "../../lib/format";
import { StatusText } from "../../presentation/shared";

import { OrderCompensationWorkspace } from "../../features/operations/OrderCompensationWorkspace";

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
      <PageHeading title={scenario === "ready" && summary ? "플랫폼 운영 현황" : "플랫폼 운영"} />
      {scenario === "contract-pending" || !summary ? <InlineNotice tone="info" title="운영 요약을 준비하고 있습니다" description="통합 상태와 건수는 아직 제공되지 않습니다. 아래에서 제공 중인 업무를 이용할 수 있습니다." /> : <section className="metric-grid" aria-label="운영 상태 요약">
        <OverviewCard icon={<AlertTriangle />} label="확인할 실패" value={`${summary.failureAttention}건`} hint="결제·알림·정산" />
        <OverviewCard icon={<RotateCcw />} label="정산 금액 차이" value={`${summary.settlementMismatch}건`} hint="자동으로 바꾸지 않는 정산 차이" />
        <OverviewCard icon={<CheckCircle2 />} label="오늘 정보 조회" value={`${summary.auditAccessToday}건`} hint="조회 사유가 기록된 건" />
        <OverviewCard icon={<BadgeCheck />} label="승인할 환불" value={`${summary.refundApprovals}건`} hint="한도와 권한 확인 필요" />
        <OverviewCard icon={<FileClock />} label="진행 중인 캠페인" value={`${summary.campaignInProgress}개`} hint="예약과 진행 상태" />
        <OverviewCard icon={<ListChecks />} label="지급 파일 준비" value={`${summary.payoutReady}건`} hint="파일 생성은 지급 완료가 아닙니다" />
      </section>}
      <section className="console-shortcuts">
        <Link className="surface-card shortcut-card" to="/ops/merchant-accounts"><Search /><div><strong>점주 계정 관리</strong><span>계정 조회와 임시 비밀번호 발급</span></div><ArrowRight /></Link>
        <Link className="surface-card shortcut-card" to="/ops/campaigns"><ListChecks /><div><strong>쿠폰 캠페인 관리</strong><span>캠페인 조회와 초안 작성</span></div><ArrowRight /></Link>
        <Link className="surface-card shortcut-card" to="/ops/orders"><Search /><div><strong>보상 내역 찾기</strong><span>주문 번호로 취소·거절 후속 처리 확인</span></div><ArrowRight /></Link>
      </section>
    </div>
  );
}

export function OpsOrderPage() {
  return <div className="console-page"><PageHeading title="주문 후속 처리 조회" /><OrderCompensationWorkspace /></div>;
}

const compensationStepLabels: Record<components["schemas"]["CompensationStep"]["type"], string> = {
  PAYMENT: "결제 환불", PICKUP: "픽업 예약 해제", COUPON: "쿠폰 복원", POINTS: "포인트 복원", CUSTOMER_NOTIFICATION: "고객 알림",
};

export function CompensationResult({ result }: { result: Compensation }) {
  return <section className="surface-card compensation-card"><div className="panel-heading"><div><span className="context-label">CASE {compactId(result.caseId)}</span><h2>{result.trigger === "STORE_REJECTION" ? "매장 거절 보상" : "고객 취소 보상"}</h2></div><StatusText state={result.state} /></div><div className="compensation-steps">{result.steps.map((step) => <article key={step.type}><span>{step.state === "SUCCEEDED" || step.state === "NOT_REQUIRED" ? <CheckCircle2 size={19} /> : <AlertTriangle size={19} />}</span><div><strong>{compensationStepLabels[step.type]}</strong><small><StatusText state={step.state} /> · 시도 {step.attemptCount}회{step.lastErrorCode ? ` · ${step.lastErrorCode}` : ""}</small></div></article>)}</div><p className="form-footnote">최종 갱신 {shortDateTime.format(new Date(result.updatedAt))} · 주문의 종료 상태와 보상 성공은 독립적입니다.</p></section>;
}
