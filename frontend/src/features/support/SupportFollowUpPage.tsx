import { ClipboardCheck, FileKey2, Gift, ListChecks, MessagesSquare, UserRoundCog } from "lucide-react";
import { useState, type ReactNode } from "react";
import { useSearchParams } from "react-router";
import { Button, ButtonLink, EmptyState, InlineNotice, PageHeading, Tab, TabList, TabPanel, Tabs } from "../../design-system";
import { shortDateTime } from "../../lib/format";
import { StatusText } from "../../presentation/shared";

type Workspace = "collaboration" | "actions" | "resolutions" | "compensations" | "profiles" | "break-glass";
type SupportCaseSummary = { caseId: string; state: string; assigneeLabel: string; version: number };
type CaseEvent = { reference: string; label: string; state: string; occurredAt: string };
type ActionRequest = { reference: string; action: string; state: string; approvalRoute: string; requester: string; executor: string; version: number };
type Resolution = { reference: string; outcome: string; responsibility: string; state: string; steps: readonly { label: string; state: string }[] };
type Compensation = { reference: string; benefit: string; state: string; notificationState: string };
type ProfileChange = { reference: string; purpose: string; risk: string; state: string; notificationState: string };
type BreakGlassRequest = { reference: string; field: string; purpose: string; state: string; expiresAt: string };

export type SupportFollowUpPageProps = {
  initialWorkspace?: Workspace;
  scenario?: "case-required" | "ready";
  supportCase?: SupportCaseSummary;
  events?: readonly CaseEvent[];
  actionRequests?: readonly ActionRequest[];
  resolutions?: readonly Resolution[];
  compensations?: readonly Compensation[];
  profileChanges?: readonly ProfileChange[];
  breakGlassRequests?: readonly BreakGlassRequest[];
  onCommand?: (command: string, reference: string) => Promise<void>;
};

const workspaces: Array<{ value: Workspace; label: string; icon: typeof MessagesSquare }> = [
  { value: "collaboration", label: "상담 기록", icon: MessagesSquare },
  { value: "actions", label: "주문 처리", icon: ClipboardCheck },
  { value: "resolutions", label: "해결 현황", icon: ListChecks },
  { value: "compensations", label: "보상", icon: Gift },
  { value: "profiles", label: "정보 변경", icon: UserRoundCog },
  { value: "break-glass", label: "긴급 열람", icon: FileKey2 },
];

const actionLabels: Record<string, string> = {
  PICKUP_RESCHEDULE: "픽업 시간 변경",
};

const approvalRouteLabels: Record<string, string> = {
  SUPPORT_MANAGER: "고객센터 관리자",
};

const resolutionOutcomeLabels: Record<string, string> = {
  PARTIAL_REFUND: "부분 환불",
};

const responsibilityLabels: Record<string, string> = {
  STORE: "매장",
};

const personalFieldLabels: Record<string, string> = {
  CUSTOMER_PRIMARY_PHONE: "기본 연락처",
};

const purposeLabels: Record<string, string> = {
  ACTIVE_FRAUD: "진행 중인 부정 사용 확인",
};

const riskLabels: Record<string, string> = {
  R3: "3단계",
};

export function SupportFollowUpPage({
  initialWorkspace = "collaboration",
  scenario = "case-required",
  supportCase,
  events = [],
  actionRequests = [],
  resolutions = [],
  compensations = [],
  profileChanges = [],
  breakGlassRequests = [],
  onCommand,
}: SupportFollowUpPageProps) {
  const [searchParams] = useSearchParams();
  const [workspace, setWorkspace] = useState<Workspace>(initialWorkspace);
  const [commandStatus, setCommandStatus] = useState("");
  const [busyCommand, setBusyCommand] = useState("");
  const caseReference = supportCase?.caseId ?? searchParams.get("caseId");

  async function runCommand(command: string, reference: string, success: string) {
    if (!onCommand) return;
    setBusyCommand(`${command}:${reference}`);
    setCommandStatus("");
    try {
      await onCommand(command, reference);
      setCommandStatus(success);
    } finally {
      setBusyCommand("");
    }
  }

  if (scenario === "case-required" || !supportCase) {
    return (
      <div className="console-page support-follow-up-page">
        <PageHeading title="상담 후속 업무" />
        <EmptyState
          title="상담 건을 먼저 열어 주세요"
          description={caseReference ? `${caseReference} 상담 건의 후속 업무를 아직 불러올 수 없습니다.` : "검색 결과에서 상담 건을 연 뒤 필요한 업무를 선택해 주세요."}
          action={<ButtonLink to={caseReference ? `/support?caseId=${encodeURIComponent(caseReference)}` : "/support"}>상담 건 열기</ButtonLink>}
        />
      </div>
    );
  }

  const commandButton = (label: string, command: string, reference: string, success = "요청을 보냈습니다") => (
    <Button
      variant="secondary"
      disabled={!onCommand}
      loading={busyCommand === `${command}:${reference}`}
      onClick={() => void runCommand(command, reference, success)}
    >
      {label}
    </Button>
  );

  return (
    <div className="console-page support-follow-up-page">
      <PageHeading title="상담 후속 업무" />
      <section className="surface-card follow-up-case-summary" aria-label="현재 상담 건">
        <div><span className="context-label">상담 건</span><strong>{supportCase.caseId}</strong></div>
        <div><span className="context-label">담당자</span><strong>{supportCase.assigneeLabel}</strong></div>
        <StatusText state={supportCase.state} />
      </section>
      <InlineNotice tone="info" title="현재 상담 건으로 처리합니다" description="실행할 때 최신 상태와 권한을 다시 확인합니다." />
      {commandStatus ? <p className="operation-success" role="status">{commandStatus}</p> : null}
      <Tabs value={workspace} onValueChange={(value) => { setWorkspace(value as Workspace); setCommandStatus(""); }}>
        <TabList label="상담 후속 업무 선택">
          {workspaces.map(({ value, label, icon: Icon }) => <Tab key={value} value={value}><Icon size={17} aria-hidden="true" /> {label}</Tab>)}
        </TabList>

        <TabPanel value="collaboration">
          <FollowUpSection title="담당자와 상담 기록" eyebrow="상담 기록">
            {events.length === 0 ? <BoundedEmpty title="남겨진 상담 기록이 없습니다" /> : <ol className="follow-up-event-list">{events.map((event) => <li className="surface-card follow-up-card" key={event.reference}><div><span className="context-label">{shortDateTime.format(new Date(event.occurredAt))}</span><strong>{event.label}</strong></div><StatusText state={event.state} /></li>)}</ol>}
            <div className="follow-up-actions">
              {commandButton("담당자 바꾸기", "REASSIGN_CASE", supportCase.caseId)}
              {commandButton("상담 내용 남기기", "RECORD_INTERACTION", supportCase.caseId)}
              {commandButton("내부 메모 남기기", "RECORD_INTERNAL_NOTE", supportCase.caseId)}
            </div>
          </FollowUpSection>
        </TabPanel>

        <TabPanel value="actions">
          <FollowUpSection title="주문 요청 처리" eyebrow="승인과 실행을 따로 처리">
            <CardGrid items={actionRequests} empty="처리할 주문 요청이 없습니다" render={(item) => <article className="surface-card follow-up-card" key={item.reference}><CardHeader reference={item.reference} state={item.state} /><h3>{actionLabels[item.action] ?? item.action}</h3><dl className="detail-list"><div><dt>승인 담당</dt><dd>{approvalRouteLabels[item.approvalRoute] ?? item.approvalRoute}</dd></div><div><dt>요청자</dt><dd>{item.requester}</dd></div><div><dt>실행 담당자</dt><dd>{item.executor}</dd></div></dl><div className="follow-up-actions">{commandButton("승인하기", "APPROVE_ACTION", item.reference)}{commandButton("담당자 바꾸기", "REASSIGN_ACTION", item.reference)}{commandButton("실행하기", "EXECUTE_ACTION", item.reference, "실행 요청을 보냈습니다")}</div></article>} />
          </FollowUpSection>
        </TabPanel>

        <TabPanel value="resolutions">
          <FollowUpSection title="해결 진행 상황" eyebrow="각 단계의 상태를 따로 표시">
            <CardGrid items={resolutions} empty="진행 중인 해결 업무가 없습니다" render={(item) => <article className="surface-card follow-up-card" key={item.reference}><CardHeader reference={item.reference} state={item.state} /><h3>{resolutionOutcomeLabels[item.outcome] ?? item.outcome}</h3><p>담당 {responsibilityLabels[item.responsibility] ?? item.responsibility}</p><ol className="follow-up-steps">{item.steps.map((step) => <li key={step.label}><span>{step.label}</span><StatusText state={step.state} /></li>)}</ol><div className="follow-up-actions">{commandButton("해결 작업 실행", "EXECUTE_RESOLUTION", item.reference)}{commandButton("결과 다시 확인", "RECONCILE_RESOLUTION", item.reference)}</div></article>} />
          </FollowUpSection>
        </TabPanel>

        <TabPanel value="compensations">
          <FollowUpSection title="보상과 알림" eyebrow="보상과 알림 상태를 따로 표시">
            <CardGrid items={compensations} empty="처리할 보상 업무가 없습니다" render={(item) => <article className="surface-card follow-up-card" key={item.reference}><CardHeader reference={item.reference} state={item.state} /><h3>{item.benefit}</h3><div className="panel-heading"><span>알림 상태</span><StatusText state={item.notificationState} /></div><div className="follow-up-actions">{commandButton("보상 지급", "EXECUTE_COMPENSATION", item.reference)}{commandButton("알림 다시 보내기", "RETRY_COMPENSATION_NOTIFICATION", item.reference)}</div></article>} />
          </FollowUpSection>
        </TabPanel>

        <TabPanel value="profiles">
          <FollowUpSection title="고객 정보 변경" eyebrow="변경 목적과 위험도 확인">
            <CardGrid items={profileChanges} empty="처리할 고객 정보 변경이 없습니다" render={(item) => <article className="surface-card follow-up-card" key={item.reference}><CardHeader reference={item.reference} state={item.state} /><h3>{personalFieldLabels[item.purpose] ?? item.purpose}</h3><p>위험도 {riskLabels[item.risk] ?? item.risk}</p><div className="panel-heading"><span>알림 상태</span><StatusText state={item.notificationState} /></div><div className="follow-up-actions">{commandButton("정보 변경", "EXECUTE_PROFILE_CHANGE", item.reference)}{commandButton("알림 다시 보내기", "RETRY_PROFILE_NOTIFICATION", item.reference)}</div></article>} />
          </FollowUpSection>
        </TabPanel>

        <TabPanel value="break-glass">
          <FollowUpSection title="긴급 정보 열람 요청" eyebrow="열람 항목, 만료 시간과 사후 검토">
            <CardGrid items={breakGlassRequests} empty="검토할 긴급 열람 요청이 없습니다" render={(item) => <article className="surface-card follow-up-card" key={item.reference}><CardHeader reference={item.reference} state={item.state} /><h3>{personalFieldLabels[item.field] ?? item.field}</h3><p>{purposeLabels[item.purpose] ?? item.purpose} · {shortDateTime.format(new Date(item.expiresAt))} 만료</p><div className="follow-up-actions">{commandButton("열람 승인", "APPROVE_BREAK_GLASS", item.reference)}{commandButton("정보 보기", "REVEAL_BREAK_GLASS_FIELD", item.reference)}{commandButton("검토 완료", "COMPLETE_BREAK_GLASS_REVIEW", item.reference)}</div></article>} />
          </FollowUpSection>
        </TabPanel>
      </Tabs>
    </div>
  );
}

function FollowUpSection({ title, eyebrow, children }: { title: string; eyebrow: string; children: ReactNode }) {
  return <section className="follow-up-workspace"><div className="panel-heading"><div><span className="context-label">{eyebrow}</span><h2>{title}</h2></div></div>{children}</section>;
}

function CardHeader({ reference, state }: { reference: string; state: string }) {
  return <div className="panel-heading"><span className="context-label">{reference}</span><StatusText state={state} /></div>;
}

function BoundedEmpty({ title }: { title: string }) {
  return <EmptyState title={title} description="현재 표시할 항목이 없습니다." />;
}

function CardGrid<T>({ items, empty, render }: { items: readonly T[]; empty: string; render: (item: T) => ReactNode }) {
  return items.length === 0 ? <BoundedEmpty title={empty} /> : <div className="follow-up-card-grid">{items.map(render)}</div>;
}
