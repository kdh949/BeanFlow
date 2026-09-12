import { SupportSubjectPicker, type SupportSubjectSelection } from "./SupportSubjectPicker";
import { OperatorTargetPicker, type OperatorSelection } from "../operations/OperatorTargetPicker";
import { useCallback, useState } from "react";
import { useParams } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, ButtonLink, EmptyState, InlineNotice, LoadingState, PageHeading, SelectField, Tab, TabList, TabPanel, Tabs, TextAreaField, TextField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { seoulInstant } from "../../lib/seoulDateTime";
import { useResource } from "../shared/useResource";
import { casePriorityLabels, caseStateLabels, relationshipLabels, subjectLabels, supportSubjectLabel } from "./supportCaseLabels";
import { useSupportCommand } from "./useSupportCommand";
type State = components["schemas"]["SupportCaseState"];
const transitions: Record<State, State[]> = { OPEN: ["IN_PROGRESS"], IN_PROGRESS: ["WAITING", "RESOLVED"], WAITING: ["IN_PROGRESS"], RESOLVED: ["CLOSED"], CLOSED: [] };
const channelLabels: Record<components["schemas"]["SupportInteractionChannel"], string> = { PHONE: "전화", CHAT: "채팅", EMAIL: "이메일", IN_PERSON: "대면", SYSTEM: "시스템" };
const directionLabels: Record<components["schemas"]["SupportInteractionDirection"], string> = { INBOUND: "받은 연락", OUTBOUND: "보낸 연락", INTERNAL: "내부 접촉" };
/** Case commands remain separate from privileged verification and raw personal-data views. */
export function SupportCaseManagementPage() {
  const { caseId = "" } = useParams();
  return <CaseManagement key={caseId} caseId={caseId} />;
}
function CaseManagement({ caseId }: { caseId: string }) {
  const resource = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/cases/{caseId}", { params: { path: { caseId } } })), [caseId]));
  const command = useSupportCommand(`case-management:${caseId}`, resource.reload);
  const [workspace, setWorkspace] = useState("state");
  const [targetState, setTargetState] = useState<State | "">("");
  const [stateReason, setStateReason] = useState("");
  const [assignee, setAssignee] = useState<OperatorSelection | null>(null);
  const [assignReason, setAssignReason] = useState("");
  const [channel, setChannel] = useState<components["schemas"]["SupportInteractionChannel"] | "">("");
  const [direction, setDirection] = useState<components["schemas"]["SupportInteractionDirection"] | "">("");
  const [occurredAt, setOccurredAt] = useState("");
  const [summary, setSummary] = useState("");
  const [note, setNote] = useState("");
  const [noteReason, setNoteReason] = useState("");
  const [subjectType, setSubjectType] = useState<components["schemas"]["SupportSubjectType"] | "">("");
  const [subject, setSubject] = useState<SupportSubjectSelection | null>(null);
  const [relationship, setRelationship] = useState<components["schemas"]["SupportSubjectRelationship"] | "">("");
  const [linkReason, setLinkReason] = useState("");
  const [linkId, setLinkId] = useState("");
  const [unlinkReason, setUnlinkReason] = useState("");
  const [notice, setNotice] = useState("");
  const [validation, setValidation] = useState("");
  const current = resource.state.status === "ready" ? resource.state.value : null;
  const locked = command.busy || command.pending || resource.refreshing;
  const availableStates = current ? transitions[current.state] : [];
  const selectedState = targetState && availableStates.includes(targetState) ? targetState : "";
  const selectedLink = linkId ? current?.subjectLinks.find(link => link.linkId === linkId) : current?.subjectLinks[0];
  function perform(operation: string, body: unknown, run: (key: string) => Promise<unknown>, message: string, clear?: () => void) {
    if (locked || !current || current.state === "CLOSED") return;
    setNotice(""); setValidation("");
    command.submit(JSON.stringify({ caseId, operation, body }), run, () => { setNotice(message); clear?.(); });
  }
  function transition() {
    if (!current || !selectedState || !stateReason.trim()) return;
    const body = { targetState: selectedState, expectedVersion: current.version, reason: stateReason.trim() };
    perform("state", body, async key => unwrap(await operationsApi.POST("/support/cases/{caseId}/status-transitions", { params: { path: { caseId }, header: { "Idempotency-Key": key } }, body })), "상담 상태를 변경했습니다", () => { setTargetState(""); setStateReason(""); });
  }
  function assign() {
    if (!current || !assignee || !assignReason.trim()) return;
    const body = { assigneeId: assignee.operatorId, expectedVersion: current.version, reason: assignReason.trim() };
    perform("assign", body, async key => unwrap(await operationsApi.POST("/support/cases/{caseId}/assignments", { params: { path: { caseId }, header: { "Idempotency-Key": key } }, body })), "담당자를 배정했습니다", () => { setAssignee(null); setAssignReason(""); });
  }
  function recordInteraction() {
    if (!channel || !direction || !summary.trim()) return;
    let instant: string;
    try { instant = seoulInstant(occurredAt); if (Date.parse(instant) > Date.now()) throw new Error(); } catch { setValidation("접촉 시각은 현재까지의 유효한 한국 시간으로 입력해 주세요."); return; }
    const body = { channel, direction, occurredAt: instant, redactedSummary: summary.trim() };
    perform("interaction", body, async key => unwrap(await operationsApi.POST("/support/cases/{caseId}/interactions", { params: { path: { caseId }, header: { "Idempotency-Key": key } }, body })), "접촉 기록을 추가했습니다", () => { setSummary(""); setOccurredAt(""); });
  }
  function recordNote() {
    if (!note.trim() || !noteReason.trim()) return;
    const body = { content: note.trim(), reason: noteReason.trim() };
    perform("note", body, async key => unwrap(await operationsApi.POST("/support/cases/{caseId}/notes", { params: { path: { caseId }, header: { "Idempotency-Key": key } }, body })), "내부 노트를 추가했습니다", () => { setNote(""); setNoteReason(""); });
  }
  function linkSubject() {
    if (!subjectType || !subject || !relationship || !linkReason.trim()) return;
    const body = { subjectType, subjectId: subject!.subjectId, relationship, reason: linkReason.trim() };
    perform("link", body, async key => unwrap(await operationsApi.POST("/support/cases/{caseId}/subject-links", { params: { path: { caseId }, header: { "Idempotency-Key": key } }, body })), "대상을 연결했습니다", () => { setSubject(null); setLinkReason(""); });
  }
  function unlinkSubject() {
    if (!current || !selectedLink || !unlinkReason.trim()) return;
    const body = { expectedVersion: current.version, reason: unlinkReason.trim() };
    const targetLinkId = selectedLink.linkId;
    perform("unlink", { targetLinkId, ...body }, async key => unwrap(await operationsApi.DELETE("/support/cases/{caseId}/subject-links/{linkId}", { params: { path: { caseId, linkId: targetLinkId }, header: { "Idempotency-Key": key } }, body })), "대상 연결을 해제했습니다", () => { setLinkId(""); setUnlinkReason(""); });
  }
  return <div className="console-page management-workspace"><PageHeading title="상담 관리" action={<ButtonLink variant="secondary" to="/support/cases">상담 목록</ButtonLink>} />{resource.state.status === "ready" && resource.state.value.customerInquiryId ? <ButtonLink variant="secondary" to={`/support/inquiries/${resource.state.value.customerInquiryId}`}>고객 공개 문의와 답변</ButtonLink> : null}{notice ? <InlineNotice tone="info" announce="polite" title={notice} description="현재 상담 상태를 다시 확인합니다." /> : null}{validation ? <InlineNotice tone="warning" announce="assertive" title="기록 입력을 확인해 주세요" description={validation} /> : null}{command.failure ? command.failure instanceof ApiRequestError && command.failure.code === "ORDER_STATE_CONFLICT" ? <InlineNotice tone="warning" announce="assertive" title="상담 상태가 변경되었습니다" description="현재 상태와 버전을 확인한 뒤 다시 처리해 주세요." /> : <ErrorState error={command.failure} /> : null}{command.pending ? <InlineNotice tone="warning" announce="polite" title="요청 결과를 확인하지 못했습니다" description="입력한 내용을 유지했습니다. 같은 요청으로 처리 결과를 확인해 주세요." action={<Button loading={command.busy} onClick={() => void command.retry()}>같은 요청 결과 확인</Button>} /> : null}{resource.state.status === "loading" ? <LoadingState label="현재 상담 상태를 불러오는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : current ? <><section className="surface-card management-card management-workspace"><h2 className="support-case-reference">상담 ID {caseId}</h2><p><StatusText state={current.state} label={caseStateLabels[current.state]} /> · {casePriorityLabels[current.priority]} · 버전 {current.version}</p><dl className="detail-list"><div><dt>담당자</dt><dd className="support-case-reference">{current.assigneeDisplay?.state === "AVAILABLE" ? current.assigneeDisplay.loginName : "조직 로그인 이름 미등록"}</dd></div><div><dt>접수 시각</dt><dd>{fullDateTime.format(new Date(current.openedAt))}</dd></div>{current.closedAt ? <div><dt>종료 시각</dt><dd>{fullDateTime.format(new Date(current.closedAt))}</dd></div> : null}</dl><div className="button-row"><ButtonLink variant="secondary" to={`/support?caseId=${encodeURIComponent(caseId)}`}>본인확인·상담 업무</ButtonLink><ButtonLink variant="secondary" to={`/support/follow-up?caseId=${encodeURIComponent(caseId)}`}>상담 이력·후속 업무</ButtonLink><Button variant="secondary" disabled={locked} onClick={resource.reload}>상담 상태 새로고침</Button></div></section>{current.state === "CLOSED" ? <InlineNotice title="종료된 상담은 변경할 수 없습니다" description="기록을 확인하려면 상담 이력으로 이동해 주세요." /> : <Tabs value={workspace} onValueChange={value => { if (!locked) setWorkspace(value); }}><TabList label="상담 관리 업무"><Tab value="state">상태 변경</Tab><Tab value="assign">담당자 배정</Tab><Tab value="interaction">접촉 기록</Tab><Tab value="note">내부 노트</Tab><Tab value="subjects">대상 연결</Tab></TabList><TabPanel value="state"><form className="surface-card management-card management-workspace" onSubmit={event => { event.preventDefault(); transition(); }}><SelectField label="변경할 상담 상태" value={selectedState} onValueChange={value => setTargetState(value as State)} disabled={locked}><option value="">상태 선택</option>{availableStates.map(value => <option key={value} value={value}>{caseStateLabels[value]}</option>)}</SelectField><TextAreaField label="상태 변경 사유" value={stateReason} onValueChange={setStateReason} maxLength={500} required disabled={locked} /><Button type="submit" loading={command.busy} disabled={locked || !selectedState || !stateReason.trim()}>상담 상태 변경</Button></form></TabPanel><TabPanel value="assign"><form className="surface-card management-card management-workspace" onSubmit={event => { event.preventDefault(); assign(); }}><OperatorTargetPicker label="새 상담 담당자" purpose="CASE_ASSIGNMENT" value={assignee} onSelect={setAssignee} disabled={locked} /><TextAreaField label="배정 사유" value={assignReason} onValueChange={setAssignReason} maxLength={500} required disabled={locked} /><Button type="submit" loading={command.busy} disabled={locked || !assignee || !assignReason.trim()}>담당자 배정</Button></form></TabPanel><TabPanel value="interaction"><form className="surface-card management-card management-workspace" onSubmit={event => { event.preventDefault(); recordInteraction(); }}><div className="management-card-grid"><SelectField label="접촉 채널" value={channel} onValueChange={value => setChannel(value as typeof channel)} disabled={locked}><option value="">채널 선택</option>{Object.entries(channelLabels).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</SelectField><SelectField label="접촉 방향" value={direction} onValueChange={value => setDirection(value as typeof direction)} disabled={locked}><option value="">방향 선택</option>{Object.entries(directionLabels).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</SelectField><TextField label="접촉 시각 (한국 시간)" type="datetime-local" value={occurredAt} onValueChange={setOccurredAt} required disabled={locked} /></div><TextAreaField label="비식별 접촉 요약" value={summary} onValueChange={setSummary} maxLength={1000} required disabled={locked} description="이미 이루어진 접촉 내용을 개인정보 없이 요약합니다." /><Button type="submit" loading={command.busy} disabled={locked || !channel || !direction || !occurredAt || !summary.trim()}>접촉 기록 추가</Button></form></TabPanel><TabPanel value="note"><form className="surface-card management-card management-workspace" onSubmit={event => { event.preventDefault(); recordNote(); }}><TextAreaField label="내부 노트 내용" value={note} onValueChange={setNote} maxLength={2000} required disabled={locked} description="상담 담당자용 기록입니다. 고객에게 공개되지 않습니다." /><TextAreaField label="노트 작성 사유" value={noteReason} onValueChange={setNoteReason} maxLength={500} required disabled={locked} /><Button type="submit" loading={command.busy} disabled={locked || !note.trim() || !noteReason.trim()}>내부 노트 추가</Button></form></TabPanel><TabPanel value="subjects"><div className="management-workspace"><form className="surface-card management-card management-workspace" onSubmit={event => { event.preventDefault(); linkSubject(); }}><h2>대상 연결 추가</h2><div className="management-card-grid"><SelectField label="연결 대상 유형" value={subjectType} onValueChange={value => { setSubjectType(value as typeof subjectType); setSubject(null); }} disabled={locked}><option value="">유형 선택</option>{Object.entries(subjectLabels).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</SelectField>{subjectType ? <SupportSubjectPicker key={subjectType} subjectType={subjectType} caseId={caseId} value={subject} onSelect={setSubject} disabled={locked} /> : null}<SelectField label="상담과의 관계" value={relationship} onValueChange={value => setRelationship(value as typeof relationship)} disabled={locked}><option value="">관계 선택</option>{Object.entries(relationshipLabels).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</SelectField></div><TextAreaField label="연결 사유" value={linkReason} onValueChange={setLinkReason} maxLength={500} required disabled={locked} /><Button type="submit" loading={command.busy} disabled={locked || !subjectType || !subject || !relationship || !linkReason.trim()}>상담 대상 연결</Button></form>{current.subjectLinks.length ? <form className="surface-card management-card management-workspace" onSubmit={event => { event.preventDefault(); unlinkSubject(); }}><h2>현재 대상 연결 해제</h2><SelectField label="해제할 대상 연결" value={selectedLink?.linkId ?? ""} onValueChange={setLinkId} disabled={locked}><option value="">해제할 연결 선택</option>{current.subjectLinks.map(link => <option key={link.linkId} value={link.linkId}>{supportSubjectLabel(link)} · {relationshipLabels[link.relationship]}</option>)}</SelectField><TextAreaField label="연결 해제 사유" value={unlinkReason} onValueChange={setUnlinkReason} maxLength={500} required disabled={locked} /><Button variant="danger" type="submit" loading={command.busy} disabled={locked || !selectedLink || !unlinkReason.trim()}>선택한 연결 해제</Button></form> : <EmptyState title="연결된 대상이 없습니다" description="상담에 필요한 고객·매장·주문·배송을 연결할 수 있습니다." />}</div></TabPanel></Tabs>}</> : null}</div>;
}
