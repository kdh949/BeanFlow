import { AlertTriangle, ReceiptText, RotateCcw, ScrollText, SearchCheck } from "lucide-react";
import { useState } from "react";
import { Button, EmptyState, InlineNotice, PageHeading, Tab, TabList, TabPanel, Tabs } from "../../design-system";
import { StatusText } from "../../presentation/shared";
import { shortDateTime, won } from "../../lib/format";

type Workspace = "failures" | "settlements" | "audit";

const queueTypeLabels: Record<OperationsFailureSummary["type"], string> = {
  PAYMENT: "결제",
  NOTIFICATION: "알림",
  SETTLEMENT: "정산",
};

const auditActionLabels: Record<string, string> = {
  SUPPORT_DATA_REVEAL: "고객 정보 열람",
};

function auditTargetLabel(target: string) {
  return target.startsWith("CASE ") ? `상담 건 ${target.slice(5)}` : target;
}

export type OperationsFailureSummary = { type: "PAYMENT" | "NOTIFICATION" | "SETTLEMENT"; attentionCount: number; oldestAge: string };
export type OperationsFailureItem = {
  workReference: string;
  queueType: "PAYMENT" | "NOTIFICATION" | "SETTLEMENT";
  sourceState: string;
  attentionState: string;
  attemptCount: number;
  attemptCountAvailable: boolean;
  updatedAt: string;
  correlationId: string;
  summary: string;
  allowedActions: readonly string[];
};
export type OperationsSettlementReconciliation = {
  settlementBatchId: string;
  storeName: string;
  settlementDate: string;
  state: string;
  reconciliationState: "CONSISTENT" | "MISMATCH" | "INCOMPLETE";
  storedNetKrw: number;
  computedNetKrw: number;
  differenceKrw: number;
  reason: string;
};
export type OperationsAuditRecord = {
  auditRecordId: string;
  occurredAt: string;
  actor: string;
  action: string;
  target: string;
  reason: string;
  correlationId: string;
};

export type OperationsRecoveryPageProps = {
  initialWorkspace?: Workspace;
  scenario?: "contract-pending" | "ready";
  failureSummary?: readonly OperationsFailureSummary[];
  failureItems?: readonly OperationsFailureItem[];
  settlements?: readonly OperationsSettlementReconciliation[];
  auditRecords?: readonly OperationsAuditRecord[];
  onRetryFailure?: (workReference: string) => Promise<void>;
};

export function OperationsRecoveryPage({
  initialWorkspace = "failures",
  scenario = "contract-pending",
  failureSummary = [],
  failureItems = [],
  settlements = [],
  auditRecords = [],
  onRetryFailure,
}: OperationsRecoveryPageProps) {
  const [workspace, setWorkspace] = useState<Workspace>(initialWorkspace);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [retried, setRetried] = useState<string | null>(null);
  const [retryFailed, setRetryFailed] = useState(false);

  async function retry(item: OperationsFailureItem) {
    if (!onRetryFailure) return;
    setRetrying(item.workReference);
    setRetryFailed(false);
    try {
      await onRetryFailure(item.workReference);
      setRetried(item.workReference);
    } catch {
      setRetryFailed(true);
    } finally {
      setRetrying(null);
    }
  }

  return (
    <div className="console-page operations-recovery-page">
      <PageHeading title="문제 확인 및 복구" />
      <Tabs value={workspace} onValueChange={(value) => setWorkspace(value as Workspace)}>
        <TabList label="문제 확인 및 복구 업무 선택">
          <Tab value="failures"><AlertTriangle size={17} aria-hidden="true" /> 실패한 업무</Tab>
          <Tab value="settlements"><ReceiptText size={17} aria-hidden="true" /> 정산 대사</Tab>
          <Tab value="audit"><ScrollText size={17} aria-hidden="true" /> 감사 기록</Tab>
        </TabList>
        <TabPanel value="failures">
          <RecoverySection eyebrow="결제 · 알림 · 정산" title="실패한 업무">
            {scenario === "contract-pending" ? <PendingProjection /> : failureItems.length === 0 ? <EmptyState title="확인할 실패 업무가 없습니다" description="현재 확인할 실패 업무가 없습니다." /> : <>
              <section className="metric-grid" aria-label="실패 유형별 확인 필요 건수">
                {failureSummary.map((item) => <article className="metric-card" key={item.type}><span><AlertTriangle aria-hidden="true" /></span><small>{queueTypeLabels[item.type]}</small><strong>{item.attentionCount}건</strong><p>가장 오래된 건 {item.oldestAge}</p></article>)}
              </section>
              <div className="recovery-list">{failureItems.map((item) => <article className="surface-card recovery-card" key={item.workReference}><div className="panel-heading"><div><span className="context-label">{queueTypeLabels[item.queueType]} · {item.workReference}</span><h3>{item.summary}</h3></div><StatusText state={item.attentionState} /></div><dl className="detail-list"><div><dt>처리 상태</dt><dd><StatusText state={item.sourceState} /></dd></div><div><dt>처리 시도</dt><dd>{item.attemptCountAvailable ? `${item.attemptCount}회` : "제공되지 않음"}</dd></div><div><dt>추적 ID (Correlation ID)</dt><dd><code>{item.correlationId}</code></dd></div></dl><p className="form-footnote">최종 갱신 {shortDateTime.format(new Date(item.updatedAt))}</p>{item.allowedActions.includes("RETRY_RECONCILIATION") ? <Button variant="secondary" loading={retrying === item.workReference} disabled={!onRetryFailure || retried === item.workReference} onClick={() => void retry(item)}><RotateCcw size={16} aria-hidden="true" /> 다시 처리</Button> : null}{retried === item.workReference ? <p className="operation-success" role="status">다시 처리 요청을 보냈습니다</p> : null}</article>)}</div>
              {retryFailed ? <InlineNotice tone="danger" announce="assertive" title="다시 처리하지 못했습니다" description="최신 상태를 확인한 뒤 다시 시도해 주세요." /> : null}
            </>}
          </RecoverySection>
        </TabPanel>
        <TabPanel value="settlements">
          <RecoverySection eyebrow="저장 금액과 원본 금액 비교" title="정산 대사">
            {scenario === "contract-pending" ? <PendingProjection /> : settlements.length === 0 ? <EmptyState title="대사할 정산이 없습니다" description="선택한 기간에 조회 가능한 정산 배치가 없습니다." /> : <div className="recovery-list">{settlements.map((item) => <article className="surface-card recovery-card" key={item.settlementBatchId}><div className="panel-heading"><div><span className="context-label">{item.settlementDate} · {item.storeName}</span><h3>{item.settlementBatchId}</h3></div><StatusText state={item.reconciliationState} /></div><dl className="detail-list"><div><dt>저장된 정산액</dt><dd className="bf-num">{won.format(item.storedNetKrw)}</dd></div><div><dt>다시 계산한 정산액</dt><dd className="bf-num">{won.format(item.computedNetKrw)}</dd></div><div><dt>금액 차이</dt><dd className="bf-num">{won.format(item.differenceKrw)}</dd></div></dl><InlineNotice tone={item.reconciliationState === "MISMATCH" ? "warning" : "info"} title={item.reconciliationState === "MISMATCH" ? "정산 금액이 다릅니다" : "정산 확인 결과"} description={item.reason} /></article>)}</div>}
          </RecoverySection>
        </TabPanel>
        <TabPanel value="audit">
          <RecoverySection eyebrow="조회 사유와 담당자 기록" title="감사 기록">
            {scenario === "contract-pending" ? <PendingProjection /> : auditRecords.length === 0 ? <EmptyState title="감사 기록이 없습니다" description="현재 조회할 감사 기록이 없습니다." /> : <div className="recovery-list">{auditRecords.map((item) => <article className="surface-card recovery-card" key={item.auditRecordId}><div className="panel-heading"><div><span className="context-label">{auditActionLabels[item.action] ?? item.action}</span><h3>{auditTargetLabel(item.target)}</h3></div><SearchCheck aria-hidden="true" /></div><dl className="detail-list"><div><dt>처리한 사람</dt><dd>{item.actor}</dd></div><div><dt>사유</dt><dd>{item.reason}</dd></div><div><dt>추적 ID</dt><dd><code>{item.correlationId}</code></dd></div></dl><p className="form-footnote">{shortDateTime.format(new Date(item.occurredAt))}</p></article>)}</div>}
          </RecoverySection>
        </TabPanel>
      </Tabs>
    </div>
  );
}

function RecoverySection({ eyebrow, title, children }: { eyebrow: string; title: string; children: React.ReactNode }) {
  return <section className="recovery-workspace"><div className="panel-heading"><div><span className="context-label">{eyebrow}</span><h2>{title}</h2></div></div>{children}</section>;
}

function PendingProjection() {
  return <InlineNotice tone="danger" announce="assertive" title="문제 확인 화면을 준비하고 있습니다" description="지금은 실패 업무, 정산과 감사 기록을 조회할 수 없습니다." />;
}
