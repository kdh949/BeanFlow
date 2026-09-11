import { SupportCaseQueueSummary } from "./SupportCaseQueueSummary";
import { supportCaseTitle } from "./supportCaseLabels";
import { SupportSubjectPicker, type SupportSubjectSelection } from "./SupportSubjectPicker";
import { OperatorTargetPicker, type OperatorSelection } from "../operations/OperatorTargetPicker";
import { useCallback, useEffect, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, ButtonLink, EmptyState, InlineNotice, LoadingState, PageHeading, SelectField, Tab, TabList, TabPanel, Tabs, TextAreaField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { caseCategoryLabels, casePriorityLabels, caseRequesterLabels, caseStateLabels } from "./supportCaseLabels";
import { useSupportCommand } from "./useSupportCommand";
/** Bounded case directory and explicit intake for all supported requester categories. */
export function SupportCaseDirectoryPage() {
  const [tab, setTab] = useState("list");
  const [intakeBusy, setIntakeBusy] = useState(false);
  return <div className="console-page"><PageHeading title="상담 목록" action={<ButtonLink variant="secondary" to="/support">고객 정보 검색</ButtonLink>} /><Tabs value={tab} onValueChange={value => { if (!intakeBusy) setTab(value); }}><TabList label="상담 접수 업무"><Tab value="list">상담 목록</Tab><Tab value="create">새 상담 접수</Tab></TabList><TabPanel value="list"><div className="management-workspace"><SupportCaseQueueSummary /><CaseDirectory /></div></TabPanel><TabPanel value="create"><CaseIntake onBusy={setIntakeBusy} /></TabPanel></Tabs></div>;
}
function CaseDirectory() {
  const [state, setState] = useState<components["schemas"]["SupportCaseState"] | "">("");
  const [category, setCategory] = useState<components["schemas"]["SupportInquiryCategory"] | "">("");
  const [priority, setPriority] = useState<components["schemas"]["SupportCasePriority"] | "">("");
  const [scope, setScope] = useState("all");
  const [assignee, setAssignee] = useState<OperatorSelection | null>(null);
  const [query, setQuery] = useState<{ state?: components["schemas"]["SupportCaseState"]; assigneeId?: string; category?: components["schemas"]["SupportInquiryCategory"]; priority?: components["schemas"]["SupportCasePriority"]; mine?: boolean }>({});
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const cursor = cursors.at(-1);
  const resource = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/cases", { params: { query: { ...query, cursor, limit: 20 } } })), [query, cursor]));
  return <section className="management-workspace"><form className="management-card-grid" onSubmit={event => { event.preventDefault(); setCursors([undefined]); setQuery({ state: state || undefined, assigneeId: scope === "all" ? assignee?.operatorId : undefined, category: category || undefined, priority: priority || undefined, mine: scope === "mine" }); }}><SelectField label="상담 상태 필터" value={state} onValueChange={value => setState(value as typeof state)}><option value="">모든 상태</option>{Object.entries(caseStateLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField><SelectField label="상담 담당 범위" value={scope} onValueChange={setScope}><option value="all">담당자 선택 또는 전체</option><option value="mine">내 담당 상담</option></SelectField><SelectField label="문의 분류 필터" value={category} onValueChange={value => setCategory(value as typeof category)}><option value="">모든 분류</option>{Object.entries(caseCategoryLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField><SelectField label="우선순위 필터" value={priority} onValueChange={value => setPriority(value as typeof priority)}><option value="">모든 우선순위</option>{Object.entries(casePriorityLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField><OperatorTargetPicker disabled={scope === "mine"} label="상담 담당자 필터" purpose="CASE_FILTER" value={assignee} onSelect={setAssignee} /><Button type="submit">상담 검색</Button></form>{resource.state.status === "loading" ? <LoadingState label="상담 목록을 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <>{resource.state.value.items.length ? <div className="management-card-grid">{resource.state.value.items.map(item => <article key={item.caseId} className="surface-card management-card management-workspace"><h2>{supportCaseTitle(item)}</h2><p><StatusText state={item.state} label={caseStateLabels[item.state]} /> · {casePriorityLabels[item.priority]}</p><dl className="detail-list"><div><dt>담당자</dt><dd className="support-case-reference">{item.assigneeDisplay?.state === "AVAILABLE" ? item.assigneeDisplay.loginName : "조직 로그인 이름 미등록"}</dd></div><div><dt>접수</dt><dd>{fullDateTime.format(new Date(item.openedAt))}</dd></div></dl><ButtonLink to={`/support/cases/${encodeURIComponent(item.caseId)}`}>상담 관리 열기</ButtonLink></article>)}</div> : <EmptyState title="조건에 맞는 상담이 없습니다" description="다른 상태나 담당자로 검색할 수 있습니다." />}<div className="button-row"><Button variant="secondary" disabled={cursors.length === 1} onClick={() => setCursors(value => value.slice(0, -1))}>이전 상담</Button><Button variant="secondary" disabled={!resource.state.value.nextCursor} onClick={() => { if (resource.state.status === "ready") { const next = resource.state.value.nextCursor; if (next) setCursors(value => [...value, next]); } }}>다음 상담</Button></div></>}</section>;
}
function CaseIntake({ onBusy }: { onBusy: (value: boolean) => void }) {
  const [requesterType, setRequesterType] = useState<components["schemas"]["SupportRequesterType"] | "">("");
  const [reference, setReference] = useState("");
  const [subject, setSubject] = useState<SupportSubjectSelection | null>(null);
  const [operator, setOperator] = useState<OperatorSelection | null>(null);
  const searchType = requesterType === "CUSTOMER" ? "CUSTOMER" : requesterType === "STORE_OWNER" || requesterType === "STORE_MEMBER" ? "STORE" : requesterType === "RIDER" ? "DELIVERY" : null;
  const requesterReference = searchType ? subject?.subjectId ?? "" : requesterType === "INTERNAL_OPERATOR" ? operator?.operatorId ?? "" : reference.trim();
  const [category, setCategory] = useState<components["schemas"]["SupportInquiryCategory"] | "">("");
  const [priority, setPriority] = useState<components["schemas"]["SupportCasePriority"]>("NORMAL");
  const [externalReference, setExternalReference] = useState("");
  const [reason, setReason] = useState("");
  const [created, setCreated] = useState<components["schemas"]["SupportCase"] | null>(null);
  const command = useSupportCommand(() => {});
  const locked = command.busy || command.pending || Boolean(created);
  useEffect(() => { onBusy(command.busy || command.pending); return () => onBusy(false); }, [command.busy, command.pending, onBusy]);
  const valid = requesterType && requesterReference && category && reason.trim().length >= (category === "OTHER" ? 3 : 1);
  function create() {
    if (locked || !requesterType || !category || !valid) return;
    const body: components["schemas"]["CreateSupportCaseRequest"] = { requesterType, requesterReference, category, priority, reason: reason.trim(), ...(externalReference.trim() ? { externalReference: externalReference.trim() } : {}) };
    command.submit(JSON.stringify({ operation: "create-case", body }), async key => { const result = unwrap(await operationsApi.POST("/support/cases", { params: { header: { "Idempotency-Key": key } }, body })); setCreated(result); }, () => {});
  }
  return <section className="management-workspace">{created ? <InlineNotice tone="info" announce="polite" title="상담을 접수했습니다" description="접수한 상담에서 대상 연결과 후속 업무를 이어갈 수 있습니다." action={<ButtonLink to={`/support/cases/${encodeURIComponent(created.caseId)}`}>접수한 상담 관리</ButtonLink>} /> : null}{command.failure ? <ErrorState error={command.failure} /> : null}{command.pending ? <InlineNotice tone="warning" announce="polite" title="접수 결과를 확인하지 못했습니다" description="입력한 내용으로 같은 접수 요청의 결과를 확인합니다." action={<Button loading={command.busy} onClick={() => void command.retry()}>같은 요청 결과 확인</Button>} /> : null}<form className="surface-card management-card management-workspace" onSubmit={event => { event.preventDefault(); create(); }}><div className="management-card-grid"><SelectField label="요청자 유형" value={requesterType} onValueChange={value => { setRequesterType(value as typeof requesterType); setSubject(null); setOperator(null); setReference(""); }} disabled={locked}><option value="">유형 선택</option>{Object.entries(caseRequesterLabels).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</SelectField>{searchType ? <SupportSubjectPicker key={searchType} subjectType={searchType} value={subject} onSelect={setSubject} disabled={locked} /> : requesterType === "INTERNAL_OPERATOR" ? <OperatorTargetPicker label="문의한 내부 담당자" purpose="INTERNAL_REQUESTER" value={operator} onSelect={setOperator} disabled={locked} /> : requesterType ? <TextField label="요청자 설명" value={reference} onValueChange={setReference} maxLength={200} required disabled={locked} description="접수 경로와 요청자를 구분할 업무 설명을 적습니다. 개인정보 원문이나 내부 ID는 입력하지 않습니다." /> : null}<SelectField label="문의 분류" value={category} onValueChange={value => setCategory(value as typeof category)} disabled={locked}><option value="">분류 선택</option>{Object.entries(caseCategoryLabels).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</SelectField><SelectField label="우선순위" value={priority} onValueChange={value => setPriority(value as typeof priority)} disabled={locked}>{Object.entries(casePriorityLabels).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</SelectField><TextField label="외부 접수 참조 (선택)" value={externalReference} onValueChange={setExternalReference} maxLength={200} disabled={locked} /></div><TextAreaField label="접수 사유" value={reason} onValueChange={setReason} maxLength={500} required disabled={locked} /><Button type="submit" loading={command.busy} disabled={locked || !valid}>상담 접수</Button></form></section>;
}
