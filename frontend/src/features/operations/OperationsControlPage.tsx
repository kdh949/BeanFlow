import { BadgeCheck, FileClock, GitBranch, Megaphone, Network, ReceiptText } from "lucide-react";
import { useState } from "react";
import { Button, EmptyState, InlineNotice, PageHeading, Tab, TabList, TabPanel, Tabs, TextField } from "../../design-system";
import { StatusText } from "../../presentation/shared";

type Workspace = "refunds" | "disputes" | "trace" | "coupons" | "campaigns" | "payouts";
type SimpleRecord = { reference: string; state: string };
type RefundApproval = SimpleRecord & { storeName: string; amount: string; reason: string };
type DisputeRoute = SimpleRecord & { storeName: string; age: string; summary: string };
type TraceRecord = { correlationId: string; state: string; steps: readonly string[] };
type CouponJob = SimpleRecord & { campaign: string; attempts: number };
type Campaign = SimpleRecord & { title: string; window: string };
type PayoutFile = SimpleRecord & { settlementDate: string; stores: number; amount: string };

export type OperationsControlPageProps = {
  initialWorkspace?: Workspace;
  scenario?: "contract-pending" | "ready";
  refundApprovals?: readonly RefundApproval[];
  disputes?: readonly DisputeRoute[];
  traces?: readonly TraceRecord[];
  couponJobs?: readonly CouponJob[];
  campaigns?: readonly Campaign[];
  payoutFiles?: readonly PayoutFile[];
  onOpenRecord?: (reference: string) => void;
  onCreateCampaign?: (name: string) => Promise<void>;
};

const tabs: Array<{ value: Workspace; label: string; icon: typeof ReceiptText }> = [
  { value: "refunds", label: "환불 승인", icon: BadgeCheck },
  { value: "disputes", label: "이의제기", icon: GitBranch },
  { value: "trace", label: "거래 추적", icon: Network },
  { value: "coupons", label: "쿠폰 발급", icon: Megaphone },
  { value: "campaigns", label: "캠페인", icon: FileClock },
  { value: "payouts", label: "지급 파일", icon: ReceiptText },
];

export function OperationsControlPage({
  initialWorkspace = "refunds",
  scenario = "contract-pending",
  refundApprovals = [],
  disputes = [],
  traces = [],
  couponJobs = [],
  campaigns = [],
  payoutFiles = [],
  onOpenRecord,
  onCreateCampaign,
}: OperationsControlPageProps) {
  const [workspace, setWorkspace] = useState<Workspace>(initialWorkspace);
  const [campaignName, setCampaignName] = useState("");
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState(false);

  async function createCampaign() {
    if (!onCreateCampaign || campaignName.trim().length < 2) return;
    setCreating(true);
    try {
      await onCreateCampaign(campaignName.trim());
      setCreated(true);
      setCampaignName("");
    } finally {
      setCreating(false);
    }
  }

  const pending = scenario === "contract-pending";
  return (
    <div className="console-page operations-control-page">
      <PageHeading title="운영 업무" />
      <Tabs value={workspace} onValueChange={(value) => setWorkspace(value as Workspace)}>
        <TabList label="운영 업무 선택">{tabs.map(({ value, label, icon: Icon }) => <Tab key={value} value={value}><Icon size={17} aria-hidden="true" /> {label}</Tab>)}</TabList>
        <TabPanel value="refunds"><ControlSection title="환불 승인" eyebrow="승인 한도와 권한 확인">{pending ? <PendingControl /> : <RecordCards records={refundApprovals} empty="승인할 환불이 없습니다" onOpenRecord={onOpenRecord} render={(item) => <><strong>{item.storeName} · {item.amount}</strong><p>{item.reason}</p></>} />}</ControlSection></TabPanel>
        <TabPanel value="disputes"><ControlSection title="이의제기 배정" eyebrow="담당자와 대기 시간">{pending ? <PendingControl /> : <RecordCards records={disputes} empty="배정할 이의제기가 없습니다" onOpenRecord={onOpenRecord} render={(item) => <><strong>{item.storeName} · 대기 {item.age}</strong><p>{item.summary}</p></>} />}</ControlSection></TabPanel>
        <TabPanel value="trace"><ControlSection title="거래 처리 내역" eyebrow="추적 ID (Correlation ID)">{pending ? <PendingControl /> : traces.length === 0 ? <EmptyState title="거래 처리 내역이 없습니다" description="입력한 추적 ID와 일치하는 결과가 없습니다." /> : <div className="control-card-grid">{traces.map((trace) => <article className="surface-card control-card" key={trace.correlationId}><div className="panel-heading"><div><span className="context-label">추적 ID</span><h3>{trace.correlationId}</h3></div><StatusText state={trace.state} /></div><ol className="trace-steps">{trace.steps.map((step) => <li key={step}>{step}</li>)}</ol></article>)}</div>}</ControlSection></TabPanel>
        <TabPanel value="coupons"><ControlSection title="쿠폰 발급 현황" eyebrow="쿠폰 발급과 알림 상태">{pending ? <PendingControl /> : <RecordCards records={couponJobs} empty="확인할 쿠폰 발급 작업이 없습니다" onOpenRecord={onOpenRecord} render={(item) => <><strong>{item.campaign}</strong><p>처리 시도 {item.attempts}회 · 쿠폰 발급과 알림 상태를 따로 확인합니다.</p></>} />}</ControlSection></TabPanel>
        <TabPanel value="campaigns"><ControlSection title="캠페인 만들기" eyebrow="기간, 수량과 비용 부담 확인">{pending ? <PendingControl /> : <div className="console-detail-grid"><section className="control-card-grid">{campaigns.map((item) => <article className="surface-card control-card" key={item.reference}><div className="panel-heading"><div><span className="context-label">{item.window}</span><h3>{item.title}</h3></div><StatusText state={item.state} /></div></article>)}</section><form className="surface-card operation-form" onSubmit={(event) => { event.preventDefault(); void createCampaign(); }}><h3>새 캠페인</h3><TextField label="캠페인 이름" value={campaignName} onValueChange={(value) => { setCampaignName(value); setCreated(false); }} /><Button type="submit" loading={creating} disabled={!onCreateCampaign || campaignName.trim().length < 2}>초안 저장</Button>{created ? <p className="operation-success" role="status">캠페인 초안을 저장했습니다</p> : null}</form></div>}</ControlSection></TabPanel>
        <TabPanel value="payouts"><ControlSection title="정산 지급 파일" eyebrow="은행 지급 전 준비 파일">{pending ? <PendingControl /> : <RecordCards records={payoutFiles} empty="생성 가능한 지급 파일이 없습니다" onOpenRecord={onOpenRecord} render={(item) => <><strong>{item.settlementDate} · {item.amount}</strong><p>{item.stores}개 매장 · 이 파일을 만들어도 실제 지급이 완료된 것은 아닙니다.</p></>} />}</ControlSection></TabPanel>
      </Tabs>
    </div>
  );
}

function ControlSection({ title, eyebrow, children }: { title: string; eyebrow: string; children: React.ReactNode }) {
  return <section className="control-workspace"><div className="panel-heading"><div><span className="context-label">{eyebrow}</span><h2>{title}</h2></div></div>{children}</section>;
}

function PendingControl() {
  return <InlineNotice tone="danger" announce="assertive" title="이 화면을 준비하고 있습니다" description="지금은 이 업무를 조회하거나 실행할 수 없습니다." />;
}

function RecordCards<T extends SimpleRecord>({ records, empty, onOpenRecord, render }: { records: readonly T[]; empty: string; onOpenRecord?: (reference: string) => void; render: (item: T) => React.ReactNode }) {
  if (records.length === 0) return <EmptyState title={empty} description="현재 처리할 항목이 없습니다." />;
  return <div className="control-card-grid">{records.map((item) => <article className="surface-card control-card" key={item.reference}><div className="panel-heading"><span className="context-label">{item.reference}</span><StatusText state={item.state} /></div><div className="control-card-copy">{render(item)}</div><Button variant="secondary" disabled={!onOpenRecord} onClick={() => onOpenRecord?.(item.reference)}>자세히 보기</Button></article>)}</div>;
}
