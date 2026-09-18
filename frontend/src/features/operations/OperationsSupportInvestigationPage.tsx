import { caseCategoryLabels } from "../support/supportCaseLabels";
import { useCallback, useState } from "react";
import { useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { ApiRequestError, unwrap } from "../../api/client";
import { Button, ButtonLink, EmptyState, InlineNotice, LoadingState, PageHeading, SelectField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { won, fullDateTime } from "../../lib/format";
import { profilePurposes } from "../../lib/supportProfilePayload";
import { useResource } from "../shared/useResource";
type Compensation = components["schemas"]["OperationsSupportCompensationReview"];
const investigationStates: Record<string, string> = { OPEN: "검토 중", APPROVED: "승인", DENIED: "반려", RETURNED: "수정 요청", ESCALATED: "추가 조사", EXPIRED: "만료", STALE: "조건 변경" };
const approvalStates: Record<string, string> = { AWAITING_SUPPORT_MANAGER: "상담 승인 대기", AWAITING_OPERATIONS: "운영 검토 대기", READY_FOR_EXECUTION: "실행 준비", REVISION_REQUIRED: "수정 필요", REASSIGNMENT_REQUIRED: "담당자 재배정 필요", DENIED: "반려", MANUAL_REVIEW: "수동 확인 필요", STALE: "조건 변경", EXPIRED: "만료", EXECUTED: "실행 완료" };
const stale = () => new ApiRequestError(409, "SUPPORT_ACTION_REQUEST_STALE", "현재 조사와 승인안이 일치하지 않습니다");

/** Displays historical Operations investigations and directs unexecuted requests to recreation. */
export function OperationsSupportInvestigationPage() {
  const [params] = useSearchParams(), [id, setId] = useState(params.get("requestId") ?? "");
  const [showQueue, setShowQueue] = useState(!id);
  return <div className="console-page management-workspace"><PageHeading title="상담 요청 운영 검토 이력" /><p>과거 운영 조사와 승인 이력을 확인합니다.</p>
    <Button variant="secondary" onClick={() => setShowQueue(open => !open)}>{showQueue ? "검토 목록 닫기" : "검토 목록에서 선택"}</Button>
    {showQueue ? <InvestigationQueue disabled={false} onSelect={requestId => { setId(requestId); setShowQueue(false); }} /> : null}
    {id ? <Investigation key={id} id={id} /> : <EmptyState title="검토할 요청을 선택해 주세요" description="업무 종류와 상담 접수 시각을 확인해 현재 검토할 요청을 선택합니다." />}
  </div>;
}
function Investigation({ id }: { id: string }) {
  const read = useResource(useCallback(async () => {
    const review = unwrap(await operationsApi.GET("/operations/support-action-requests/{requestId}/review", { params: { path: { requestId: id } } }));
    const workflow = unwrap(await operationsApi.GET("/operations/investigations", { params: { query: { supportActionRequestId: id, revisionNumber: review.request.revisionNumber } } }));
    if (review.request.requestId !== id || workflow.investigation.supportActionRequestId !== id || workflow.investigation.revisionNumber !== review.request.revisionNumber) throw stale();
    return { ...review, workflow };
  }, [id]));
  const value = read.state.status === "ready" ? read.state.value : null;
  return <section className="surface-card management-card management-workspace" aria-label="운영 조사 이력"><h2>운영 조사 이력</h2>
    {read.state.status === "loading" ? <LoadingState label="운영 조사 이력을 읽는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : value ? <>
      <p className="support-case-reference">조사 ID {value.workflow.investigation.investigationId}</p><p>요청 {value.request.revisionNumber} · 조사 버전 {value.workflow.investigation.version}</p>
      <p><StatusText state={value.workflow.investigation.state} label={investigationStates[value.workflow.investigation.state]} /> · <StatusText state={value.request.state} label={approvalStates[value.request.state]} /></p>
      <dl className="detail-list"><dt>상담 건</dt><dd className="support-case-reference">{value.request.caseId}</dd><dt>검토 내용 해시</dt><dd className="support-case-reference">{value.request.actionPayloadDigest}</dd><dt>요청 근거 해시</dt><dd className="support-case-reference">{value.request.evidenceDigest}</dd></dl>
      {value.profile ? <h3>{profilePurposes[value.profile.purpose].label}</h3> : null}
      {value.compensation ? <CompensationReview value={value.compensation} /> : null}
      {value.request.state === "EXECUTED" ? <InlineNotice title="과거 처리 이력입니다" description="이미 실행한 업무는 새로 등록하지 않습니다. 진행 중인 후속 처리는 기존 요청에서 확인해 주세요." /> : <InlineNotice title="이전 정책의 요청입니다" description="이 요청에는 새 승인 결정을 기록할 수 없습니다. 처리할 업무가 남아 있다면 현재 담당 상담원이 같은 상담에서 새 처리 요청을 작성해 주세요." />}
      <div className="button-row"><Button variant="secondary" onClick={read.reload}>운영 조사 새로고침</Button><ButtonLink variant="secondary" to={`/support/follow-up?caseId=${value.request.caseId}`}>상담 후속 업무</ButtonLink></div>
    </> : null}
  </section>;
}
function CompensationReview({ value }: { value: Compensation }) {
  return <div className="management-workspace"><h3>고객 보상 조건</h3><p>{value.benefitType === "POINT" ? "포인트" : "쿠폰"} {won.format(value.amountKrw)}</p><p>비용 책임 · {{ PLATFORM: "플랫폼", STORE: "매장", SHARED: "분담", UNDETERMINED: "미확정" }[value.terms.responsibility]} · 플랫폼 {value.terms.platformShareBps / 100}% · 매장 {value.terms.storeShareBps / 100}%</p>
    {value.terms.evidenceBasis ? <p>비용 근거 · {{ SUPPORT_DECISION: "상담원 결정", STORE_CONSENT: "매장 동의", OPERATIONS_FINDING: "운영 조사 결과", CONTRACTUAL_RULE: "계약 규칙" }[value.terms.evidenceBasis]}</p> : null}
    {value.couponTemplate ? <p>쿠폰 사용 기한 {value.couponTemplate.validityDays}일 · 최소 사용 금액 {won.format(value.couponTemplate.minimumEligibleSubtotalKrw)}</p> : null}
    <dl className="detail-list"><dt>사고 ID</dt><dd className="support-case-reference">{value.incidentId}</dd><dt>관련 주문</dt><dd className="support-case-reference">{value.orderId ?? "관련 주문 없음"}</dd>{value.terms.costEvidenceDigest ? <><dt>비용 근거 해시</dt><dd className="support-case-reference">{value.terms.costEvidenceDigest}</dd></> : null}</dl></div>;
}

function InvestigationQueue({ disabled, onSelect }: { disabled: boolean; onSelect: (requestId: string) => void }) {
  const [state, setState] = useState<components["schemas"]["OperationsSupportInvestigationState"] | "">("");
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const cursor = cursors.at(-1);
  const read = useResource(useCallback(async () => unwrap(await operationsApi.GET("/operations/investigation-queue", { params: { query: { state: state || undefined, cursor, limit: 20 } } })), [state, cursor]));
  const labels: Record<components["schemas"]["SupportActionType"], string> = { ORDER_CANCELLATION: "주문 취소", PICKUP_RESCHEDULE: "픽업 시간 변경", POST_ACCEPTANCE_RESOLUTION: "수락 후 주문 해결", GOODWILL_COMPENSATION: "고객 불편 보상", PROFILE_CHANGE: "정보 정정" };
  return <fieldset className="catalog-fieldset management-workspace" disabled={disabled}><legend>운영 검토 요청</legend><SelectField label="검토 상태" value={state} onValueChange={value => { setState(value as typeof state); setCursors([undefined]); }}><option value="">전체 상태</option>{Object.entries(investigationStates).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
    {read.state.status === "loading" ? <LoadingState label="운영 검토 요청을 확인하는 중" /> : read.state.status === "failed" ? <ErrorState error={read.state.error} retry={read.reload} /> : <>
      {read.state.value.items.length ? read.state.value.items.map(item => <article className="surface-card management-card" key={item.investigationId}><strong>{labels[item.request.action]}</strong><p>{caseCategoryLabels[item.request.caseCategory]} · {fullDateTime.format(new Date(item.request.caseOpenedAt))} 상담 접수</p><p>승인안 {item.request.revisionNumber} · {fullDateTime.format(new Date(item.expiresAt))}까지</p><StatusText state={item.state} label={investigationStates[item.state]} />{!item.canDecide ? <p>현재 계정에서는 검토 내용만 확인할 수 있습니다.</p> : null}<Button type="button" variant="secondary" onClick={() => onSelect(item.request.requestId)}>이 검토 열기</Button></article>) : <EmptyState title="현재 조회 구간에 검토 요청이 없습니다" description={read.state.value.nextCursor ? "다음 조회 구간을 확인해 주세요." : "현재 권한과 상태에 해당하는 검토 요청이 등록되면 여기에서 확인할 수 있습니다."} />}
      <div className="button-row"><Button type="button" variant="ghost" disabled={cursors.length < 2} onClick={() => setCursors(list => list.slice(0, -1))}>이전 검토</Button><Button type="button" variant="secondary" disabled={!read.state.value.nextCursor} onClick={() => { const next = read.state.status === "ready" ? read.state.value.nextCursor : null; if (next) setCursors(list => [...list, next]); }}>다음 검토</Button><Button type="button" variant="ghost" onClick={read.reload}>검토 목록 새로고침</Button></div>
    </>}
  </fieldset>;
}
