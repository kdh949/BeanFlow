import {
  AlertTriangle,
  BadgeCheck,
  BadgeDollarSign,
  Clock3,
  ContactRound,
  FileSearch,
  Headset,
  MessageSquarePlus,
  RefreshCw,
  ShieldCheck,
  ShoppingBag,
  UserRoundSearch,
} from "lucide-react";
import type { ReactNode } from "react";
import type { components } from "../../api/schema";
import {
  Button,
  ButtonLink,
  EmptyState,
  EventTimeline,
  FeedbackState,
  FilterToolbar,
  LoadingState,
  MetricStrip,
  PageHeading,
  SelectField,
  StatusText,
  TextAreaField,
  TextField,
  WorkflowStepper,
  WorkspaceDataTable,
} from "../../design-system";

export type QueueSummary = components["schemas"]["SupportCaseQueueSummary"];
export type QueuePage = components["schemas"]["SupportCaseQueuePage"];
export type QueueItem = components["schemas"]["SupportCaseQueueItem"];
export type CaseOverview = components["schemas"]["SupportCaseOverview"];
export type ApprovalPage = components["schemas"]["SupportApprovalTaskPage"];
export type ApprovalDetail = components["schemas"]["SupportApprovalTaskDetail"];
export type ApprovalTimeline = components["schemas"]["SupportApprovalTimelinePage"];
export type CompensationPage = components["schemas"]["SupportCompensationPage"];
export type ProfileChangePage = components["schemas"]["SupportProfileChangePage"];
export type SearchResult = components["schemas"]["SupportSubjectSearchResult"];

export type ScreenStatus = "ready" | "loading" | "empty" | "error" | "permission";

function SupportPage({ title, description, actions, children }: { title: string; description: string; actions?: ReactNode; children: ReactNode }) {
  return <div className="bf-support-center-page"><div className="bf-support-center-page__heading"><PageHeading title={title} action={actions} /><p>{description}</p></div>{children}</div>;
}

function ScreenGate({ status, loadingLabel, children }: { status: ScreenStatus; loadingLabel: string; children: ReactNode }) {
  if (status === "loading") return <LoadingState label={loadingLabel} />;
  if (status === "permission") return <FeedbackState kind="error" title="이 화면을 볼 권한이 없습니다" description="권한이 변경되었을 수 있습니다. 다시 로그인하거나 관리자에게 확인해 주세요." />;
  if (status === "error") return <FeedbackState kind="error" title="데이터를 불러오지 못했습니다" description="의존 서비스 상태를 확인한 뒤 다시 시도해 주세요. 빈 결과로 대체하지 않았습니다." />;
  return <>{children}</>;
}

function Surface({ title, description, actions, children, className = "" }: { title: string; description?: string; actions?: ReactNode; children: ReactNode; className?: string }) {
  return <section className={`bf-support-surface ${className}`}><header><div><h2>{title}</h2>{description ? <p>{description}</p> : null}</div>{actions}</header><div className="bf-support-surface__body">{children}</div></section>;
}

function statusTone(state: string): "neutral" | "uncertain" | "danger" {
  if (/DENIED|FAILED|EXPIRED|STALE|CLOSED|CANCEL/.test(state)) return "danger";
  if (/WAIT|PENDING|REVIEW|RECONCIL|IN_PROGRESS|OPEN|PROCESSING/.test(state)) return "uncertain";
  return "neutral";
}

function compact(value: string) { return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value; }
function dateTime(value: string) { return new Intl.DateTimeFormat("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value)); }
function won(value: number) { return `${new Intl.NumberFormat("ko-KR").format(value)}원`; }

export function SupportQueueScreen({ status, summary, page, filters, onFilterChange, onRefresh }: { status: ScreenStatus; summary?: QueueSummary; page?: QueuePage; filters: { scope: string; state: string; priority: string }; onFilterChange?: (key: "scope" | "state" | "priority", value: string) => void; onRefresh?: () => void }) {
  return <SupportPage title="상담 대기열" description="내가 담당한 상담과 우선 처리가 필요한 Case를 확인합니다." actions={<Button variant="secondary" onClick={onRefresh}><RefreshCw size={16} />새로고침</Button>}>
    <ScreenGate status={status} loadingLabel="상담 대기열을 불러오는 중">
      {summary ? <MetricStrip items={[
        { id: "active", label: "담당 상담", value: summary.active, hint: "종료 전 전체", icon: <Headset size={20} />, tone: "accent" },
        { id: "open", label: "새 상담", value: summary.open, hint: "OPEN", icon: <MessageSquarePlus size={20} /> },
        { id: "progress", label: "처리 중", value: summary.inProgress, hint: "IN_PROGRESS", icon: <Clock3 size={20} /> },
        { id: "waiting", label: "대기", value: summary.waiting, hint: "WAITING", icon: <Clock3 size={20} /> },
        { id: "urgent", label: "긴급", value: summary.urgent, hint: "우선 확인", icon: <AlertTriangle size={20} />, tone: "warning" },
      ]} /> : null}
      <FilterToolbar>
        <SelectField label="조회 범위" value={filters.scope} onValueChange={(value) => onFilterChange?.("scope", value)}><option value="MINE">내 담당</option><option value="ALL">전체</option></SelectField>
        <SelectField label="상태" value={filters.state} onValueChange={(value) => onFilterChange?.("state", value)}><option value="">전체 상태</option><option value="OPEN">새 상담</option><option value="IN_PROGRESS">처리 중</option><option value="WAITING">대기</option><option value="RESOLVED">해결</option></SelectField>
        <SelectField label="우선순위" value={filters.priority} onValueChange={(value) => onFilterChange?.("priority", value)}><option value="">전체 우선순위</option><option value="NORMAL">보통</option><option value="HIGH">높음</option><option value="URGENT">긴급</option></SelectField>
      </FilterToolbar>
      <Surface title="상담 목록" description={page ? `${page.items.length}건을 표시합니다.` : undefined}>
        <WorkspaceDataTable caption="상담 대기열" columns={[
          { key: "case", label: "상담 건", width: "18%" }, { key: "subject", label: "주요 대상", width: "22%" }, { key: "category", label: "문의 유형" }, { key: "priority", label: "우선순위", align: "center" }, { key: "state", label: "상태", align: "center" }, { key: "changed", label: "최근 갱신", align: "end" }, { key: "action", label: "작업", align: "end" },
        ]} rows={(page?.items ?? []).map((item) => ({ id: item.caseId, cells: {
          case: <><strong>{compact(item.caseId)}</strong><small className="bf-support-cell-note">v{item.version}</small></>,
          subject: item.primarySubject ? <><strong>{item.primarySubject.maskedDisplayName ?? item.primarySubject.subjectType}</strong><small className="bf-support-cell-note">{item.primarySubject.maskedMatchedValue ?? compact(item.primarySubject.subjectId)}</small></> : "연결 대상 없음",
          category: item.category, priority: <StatusText tone={statusTone(item.priority)}>{item.priority}</StatusText>, state: <StatusText tone={statusTone(item.state)}>{item.state}</StatusText>, changed: dateTime(item.latestChangedAt), action: <ButtonLink size="sm" variant="secondary" to={`/support/cases/${item.caseId}`}>상담 열기</ButtonLink>,
        } }))} empty={<EmptyState title="조건에 맞는 상담이 없습니다" description="필터를 변경하거나 새 문의를 접수해 주세요." />} />
      </Surface>
    </ScreenGate>
  </SupportPage>;
}

export function SupportCaseIntakeScreen({ status, criterionType, criterion, result, busy, onCriterionTypeChange, onCriterionChange, onSearch, onCreate }: { status: ScreenStatus; criterionType: string; criterion: string; result?: SearchResult; busy?: boolean; onCriterionTypeChange?: (value: string) => void; onCriterionChange?: (value: string) => void; onSearch?: () => void; onCreate?: (candidate: SearchResult["items"][number]) => void }) {
  return <SupportPage title="문의 접수" description="고객·매장·배달원을 정확 검색하고 새 상담 Case를 생성합니다.">
    <ScreenGate status={status} loadingLabel="문의 접수 화면을 준비하는 중">
      <div className="bf-support-two-column">
        <Surface title="대상자 검색" description="등록된 전화번호 또는 이메일이 완전히 일치할 때 검색합니다.">
          <FilterToolbar actions={<Button loading={busy} onClick={onSearch}>검색</Button>}>
            <SelectField label="검색 기준" value={criterionType} onValueChange={onCriterionTypeChange ?? (() => undefined)}><option value="PHONE">전화번호</option><option value="EMAIL">이메일</option></SelectField>
            <TextField label="검색 값" type={criterionType === "EMAIL" ? "email" : "tel"} value={criterion} onValueChange={onCriterionChange ?? (() => undefined)} placeholder={criterionType === "EMAIL" ? "example@domain.com" : "010-1234-5678"} />
          </FilterToolbar>
          <div className="bf-support-result-list">
            {result?.items.map((candidate) => <article key={`${candidate.subjectType}:${candidate.subjectId}`}><UserRoundSearch size={20} aria-hidden="true" /><div><strong>{candidate.maskedDisplayName}</strong><span>{candidate.maskedMatchedValue}</span><small>{candidate.subjectType}</small></div><Button size="sm" variant="secondary" onClick={() => onCreate?.(candidate)}>상담 생성</Button></article>)}
            {result && result.items.length === 0 ? <EmptyState title="검색 결과가 없습니다" description="입력값을 확인한 뒤 다시 검색해 주세요." /> : null}
            {!result ? <FeedbackState kind="empty" title="검색 전입니다" description="검색 값은 화면을 벗어나면 즉시 지웁니다." /> : null}
          </div>
        </Surface>
        <Surface title="문의 정보 입력" description="대상을 선택하면 새 Case에 연결됩니다.">
          <SelectField label="문의 유형" value="PAYMENT_OR_REFUND" onValueChange={() => undefined} disabled><option value="PAYMENT_OR_REFUND">결제·환불 문의</option></SelectField>
          <SelectField label="우선순위" value="NORMAL" onValueChange={() => undefined} disabled><option value="NORMAL">보통</option></SelectField>
          <TextAreaField label="상담 내용" value="" onValueChange={() => undefined} readOnly placeholder="상담 내용을 상세히 입력해 주세요." rows={9} />
          <FeedbackState kind="empty" title="접수 원칙" description="검색 대상과 문의 분류만 Case에 연결하며 원문 PII를 상담 메모에 복사하지 않습니다." />
        </Surface>
      </div>
    </ScreenGate>
  </SupportPage>;
}

export function SupportCaseDetailScreen({ status, overview, timeline = [], noteContent = "", interactionSummary = "", mutationMessage, busy, onNoteContentChange, onInteractionSummaryChange, onAppendNote, onAppendInteraction, onRefresh }: { status: ScreenStatus; overview?: CaseOverview; timeline?: components["schemas"]["SupportTimelineItem"][]; noteContent?: string; interactionSummary?: string; mutationMessage?: string; busy?: boolean; onNoteContentChange?: (value: string) => void; onInteractionSummaryChange?: (value: string) => void; onAppendNote?: () => void; onAppendInteraction?: () => void; onRefresh?: () => void }) {
  return <SupportPage title="상담 상세" description="Case, 관련 대상과 상담 이력을 한 화면에서 확인합니다." actions={<Button variant="secondary" onClick={onRefresh}><RefreshCw size={16} />새로고침</Button>}>
    <ScreenGate status={status} loadingLabel="상담 상세를 불러오는 중">
      {overview ? <>
        <div className="bf-support-summary-grid">
          <Surface title="고객 정보"><strong>{overview.case.primarySubject?.maskedDisplayName ?? "보호된 대상"}</strong><p>{overview.case.primarySubject?.maskedMatchedValue ?? "마스킹 정보 없음"}</p><StatusText tone={statusTone(overview.case.priority)}>{overview.case.priority}</StatusText></Surface>
          <Surface title="Case 정보"><strong>{compact(overview.case.caseId)}</strong><p>{overview.case.category} · v{overview.case.version}</p><StatusText tone={statusTone(overview.case.state)}>{overview.case.state}</StatusText></Surface>
          <Surface title="관련 주문"><strong>{overview.orders.length}건</strong><p>{overview.orders[0]?.publicReference ?? "연결 주문 없음"}</p>{overview.orders[0] ? <ButtonLink size="sm" variant="secondary" to={`/support/cases/${overview.case.caseId}/orders/${overview.orders[0].orderId}/action`}>주문 보기</ButtonLink> : null}</Surface>
        </div>
        {mutationMessage ? <p className="bf-support-action-feedback" role="status">{mutationMessage}</p> : null}
        <div className="bf-support-detail-grid">
          <Surface title="상담 메모" actions={<Button size="sm" variant="secondary" loading={busy} disabled={!noteContent.trim()} onClick={onAppendNote}>메모 추가</Button>}><TextAreaField label="새 내부 메모" value={noteContent} onValueChange={onNoteContentChange ?? (() => undefined)} placeholder="비밀정보와 고위험 개인정보를 제외하고 입력해 주세요." rows={4} /><div className="bf-support-note"><strong>저장 원칙</strong><p>성공 응답은 메모 원문을 반환하지 않고 기록 여부만 알립니다.</p><small>민감정보 필터 적용</small></div></Surface>
          <Surface title="접촉 기록" actions={<Button size="sm" variant="secondary" loading={busy} disabled={!interactionSummary.trim()} onClick={onAppendInteraction}>접촉 기록 추가</Button>}><TextAreaField label="비식별 상담 요약" value={interactionSummary} onValueChange={onInteractionSummaryChange ?? (() => undefined)} placeholder="전화 상담 결과를 비식별 요약으로 입력해 주세요." rows={4} /><div className="bf-support-note"><strong>{overview.case.latestChannel ?? "SYSTEM"}</strong><p>현재 화면에서는 전화 인바운드 기록으로 저장합니다.</p><small>{dateTime(overview.case.latestChangedAt)}</small></div></Surface>
          <Surface title="상담 타임라인"><EventTimeline items={timeline.map((item) => ({ id: item.itemId, title: item.summary, occurredAt: dateTime(item.occurredAt), description: `${item.source} · ${item.state}`, tone: statusTone(item.state) === "danger" ? "danger" : statusTone(item.state) === "uncertain" ? "warning" : "neutral" }))} /></Surface>
        </div>
      </> : <EmptyState title="상담 정보가 없습니다" description="Case가 삭제된 것이 아니라 현재 조회 범위에 없을 수 있습니다." />}
    </ScreenGate>
  </SupportPage>;
}

export type VerificationVisualState = "pending" | "invalid" | "locked" | "expired" | "verified" | "grant-pending" | "active";
export function SupportVerificationScreen({ status, state = "pending", verificationCode = "", revealedValue, onVerificationCodeChange, onIssue, onVerify, onReveal, onClear }: { status: ScreenStatus; state?: VerificationVisualState; verificationCode?: string; revealedValue?: string; onVerificationCodeChange?: (value: string) => void; onIssue?: () => void; onVerify?: () => void; onReveal?: () => void; onClear?: () => void }) {
  const failed = state === "invalid" || state === "locked" || state === "expired";
  return <SupportPage title="본인확인·정보 열람" description="본인확인과 목적 제한 열람을 단계별로 처리합니다.">
    <ScreenGate status={status} loadingLabel="본인확인 상태를 불러오는 중">
      <WorkflowStepper steps={[
        { id: "issue", label: "인증 코드 발급", state: state === "pending" ? "current" : "complete" },
        { id: "verify", label: "본인확인", state: failed ? "failed" : state === "verified" ? "current" : ["grant-pending", "active"].includes(state) ? "complete" : "upcoming" },
        { id: "grant", label: "열람 승인", state: state === "grant-pending" ? "current" : state === "active" ? "complete" : "upcoming" },
        { id: "reveal", label: "제한 시간 열람", state: state === "active" ? "current" : "upcoming" },
      ]} />
      <div className="bf-support-three-column">
        <Surface title="1. 인증 코드 발급"><TextField label="등록 전화번호" value="010-12**-5678" onValueChange={() => undefined} readOnly /><Button block onClick={onIssue}>코드 발급</Button></Surface>
          <Surface title="2. 인증 코드 검증"><TextField label="6자리 인증 코드" inputMode="numeric" maxLength={6} autoComplete="one-time-code" value={verificationCode} onValueChange={(value) => onVerificationCodeChange?.(value.replace(/\D/g, "").slice(0, 6))} /><Button block variant="secondary" disabled={verificationCode.length !== 6} onClick={onVerify}>검증</Button>{failed ? <FeedbackState kind="error" title={state === "locked" ? "인증이 잠겼습니다" : state === "expired" ? "인증 코드가 만료됐습니다" : "인증 코드가 올바르지 않습니다"} description="인증 상태를 확인한 뒤 다시 시도해 주세요." /> : null}</Surface>
        <Surface title="3. 승인된 정보 열람">{state === "active" ? <><div className="bf-support-reveal" role="status"><small>승인 범위 내 정보</small><strong>{revealedValue ?? "아직 열람하지 않음"}</strong></div><Button block onClick={revealedValue ? onClear : onReveal}>{revealedValue ? "즉시 숨김" : "정보 열람"}</Button></> : <FeedbackState kind="empty" title={state === "grant-pending" ? "승인 대기 중" : "활성 Grant가 없습니다"} description="승인된 열람 범위가 활성화되면 이 영역에서 확인할 수 있습니다." />}</Surface>
      </div>
    </ScreenGate>
  </SupportPage>;
}

export type OrderActionVisualState = "allowed" | "approval-required" | "denied" | "stale" | "resolution-required" | "unknown" | "reconciling" | "manual-review";
export function SupportOrderActionScreen({ status, overview, actionState = "allowed", action = "ORDER_CANCELLATION", verificationSessionId = "", reason = "", evidenceDigest = "", actionPayloadDigest = "", evaluation, requestState, busy, onActionChange, onVerificationSessionIdChange, onReasonChange, onEvidenceDigestChange, onActionPayloadDigestChange, onEvaluate, onRequest }: { status: ScreenStatus; overview?: components["schemas"]["SupportOrderOverview"]; actionState?: OrderActionVisualState; action?: components["schemas"]["SupportActionType"]; verificationSessionId?: string; reason?: string; evidenceDigest?: string; actionPayloadDigest?: string; evaluation?: components["schemas"]["SupportActionEvaluationResource"]; requestState?: components["schemas"]["SupportActionRequestState"]; busy?: boolean; onActionChange?: (value: components["schemas"]["SupportActionType"]) => void; onVerificationSessionIdChange?: (value: string) => void; onReasonChange?: (value: string) => void; onEvidenceDigestChange?: (value: string) => void; onActionPayloadDigestChange?: (value: string) => void; onEvaluate?: () => void; onRequest?: () => void }) {
  const digestReady = /^[a-f0-9]{64}$/.test(evidenceDigest) && /^[a-f0-9]{64}$/.test(actionPayloadDigest);
  return <SupportPage title="주문 문제 처리" description="주문 상태와 서버 계산 허용 작업을 확인하고 조치를 진행합니다.">
    <ScreenGate status={status} loadingLabel="주문 조치 가능 여부를 확인하는 중">
      {overview ? <>
        <Surface title="주문 정보"><div className="bf-support-order-summary"><div><small>주문번호</small><strong>{overview.publicReference}</strong></div><div><small>상태</small><StatusText tone={statusTone(overview.state)}>{overview.state}</StatusText></div><div><small>결제 금액</small><strong>{won(overview.payableKrw)}</strong></div><div><small>픽업 예정</small><strong>{dateTime(overview.pickupWindowStart)}</strong></div></div></Surface>
        <div className="bf-support-three-column">
          <Surface title="1. 작업 선택 및 평가"><SelectField label="작업 유형" value={action} onValueChange={(value) => onActionChange?.(value as components["schemas"]["SupportActionType"])}><option value="ORDER_CANCELLATION">주문 취소</option><option value="PICKUP_RESCHEDULE">픽업 시간 변경</option><option value="POST_ACCEPTANCE_RESOLUTION">접수 후 조정</option></SelectField><TextField label="본인확인 세션 ID" value={verificationSessionId} onValueChange={onVerificationSessionIdChange ?? (() => undefined)} placeholder="강화 본인확인 세션 UUID" /><Button block loading={busy} disabled={!verificationSessionId} onClick={onEvaluate}>가능 여부 평가</Button></Surface>
          <Surface title="2. 정책 평가 결과">{evaluation ? <><FeedbackState kind={evaluation.decision === "DENIED" ? "error" : "empty"} title={evaluation.decision} description={evaluation.reasonCodes.join(", ") || "정책 제한 사유 없음"} /><div className="bf-support-note"><strong>{evaluation.requiredVerificationLevel} · v{evaluation.targetVersion}</strong><p>{evaluation.approvalRequirements.length ? evaluation.approvalRequirements.join(" → ") : "추가 승인 없음"}</p><small>{evaluation.policyVersion}</small></div></> : <FeedbackState kind={actionState === "stale" ? "error" : "empty"} title={actionState === "stale" ? "주문 버전이 변경됐습니다" : "평가 전"} description="현재 주문 버전과 본인확인 세션으로 서버 정책을 평가합니다." />}</Surface>
          <Surface title="3. 승인 요청"><TextAreaField label="조치 사유" value={reason} onValueChange={onReasonChange ?? (() => undefined)} rows={3} placeholder="민감정보 없이 조치 사유 입력" /><TextField label="증거 SHA-256" value={evidenceDigest} onValueChange={onEvidenceDigestChange ?? (() => undefined)} placeholder="소문자 64자리" /><TextField label="조치 내용 SHA-256" value={actionPayloadDigest} onValueChange={onActionPayloadDigestChange ?? (() => undefined)} placeholder="소문자 64자리" />{requestState ? <StatusText tone={statusTone(requestState)}>{requestState}</StatusText> : null}<Button block loading={busy} disabled={!evaluation || evaluation.decision === "DENIED" || !reason.trim() || !digestReady} onClick={onRequest}>조치 요청</Button></Surface>
        </div>
      </> : <EmptyState title="연결 주문이 없습니다" description="Case에 active RELATED_ORDER link가 필요합니다." />}
    </ScreenGate>
  </SupportPage>;
}

export function SupportCompensationScreen({ status, page }: { status: ScreenStatus; page?: CompensationPage }) {
  return <SupportPage title="고객 보상" description="보상 정책 평가, 승인과 지급 상태를 추적합니다."><ScreenGate status={status} loadingLabel="보상 요청을 불러오는 중"><div className="bf-support-two-column is-wide-right"><Surface title="보상 요청 목록"><WorkspaceDataTable caption="보상 요청" columns={[{ key: "request", label: "요청" }, { key: "benefit", label: "보상" }, { key: "band", label: "등급" }, { key: "state", label: "상태" }, { key: "updated", label: "갱신", align: "end" }]} rows={(page?.items ?? []).map((item) => ({ id: item.requestId, cells: { request: compact(item.requestId), benefit: `${item.benefitType} · ${won(item.amountKrw)}`, band: <StatusText tone={statusTone(item.band)}>{item.band}</StatusText>, state: <StatusText tone={statusTone(item.state)}>{item.state}</StatusText>, updated: dateTime(item.updatedAt) } }))} /></Surface><Surface title="보상 검토"><FeedbackState kind="empty" title="보상 가능 여부 평가" description="금액·대상·rolling limit은 서버 정책 결과를 사용합니다." /><SelectField label="보상 유형" value="POINT" onValueChange={() => undefined} disabled><option value="POINT">포인트</option></SelectField><TextField label="보상 금액" value="" onValueChange={() => undefined} readOnly placeholder="평가 후 결정" /><Button block disabled>승인 요청</Button></Surface></div></ScreenGate></SupportPage>;
}

export function SupportProfileChangeScreen({ status, page }: { status: ScreenStatus; page?: ProfileChangePage }) {
  return <SupportPage title="계정·정보 변경" description="위험 등급별 승인과 owner 실행 결과를 확인합니다."><ScreenGate status={status} loadingLabel="계정 변경 요청을 불러오는 중"><div className="bf-support-two-column is-wide-left"><Surface title="변경 요청"><WorkspaceDataTable caption="프로필 변경 요청" columns={[{ key: "purpose", label: "변경 유형" }, { key: "risk", label: "위험" }, { key: "before", label: "변경 전" }, { key: "after", label: "변경 후" }, { key: "state", label: "상태" }]} rows={(page?.items ?? []).map((item) => ({ id: item.profileChangeId, cells: { purpose: item.purpose, risk: <StatusText tone={statusTone(item.riskClass)}>{item.riskClass}</StatusText>, before: item.maskedBefore ?? "실행 전", after: item.maskedAfter ?? "실행 전", state: <StatusText tone={statusTone(item.state)}>{item.state}</StatusText> } }))} /></Surface><Surface title="승인 및 실행 상태"><WorkflowStepper steps={[{ id: "request", label: "요청 접수", state: "complete" }, { id: "approval", label: "승인", state: "current" }, { id: "execute", label: "owner 실행", state: "upcoming" }, { id: "notify", label: "알림", state: "upcoming" }]} /><FeedbackState kind="empty" title="R1/R2는 직접 실행, R3/R4는 승인 필요" description="원문 변경값은 입력 순간에만 사용하고 목록에는 마스킹 결과만 표시합니다." /></Surface></div></ScreenGate></SupportPage>;
}

export function SupportApprovalsScreen({ status, page, detail, timeline, taskType = "", state = "", actionMessage, busy, onTaskTypeChange, onStateChange, onAction }: { status: ScreenStatus; page?: ApprovalPage; detail?: ApprovalDetail; timeline?: ApprovalTimeline; taskType?: string; state?: string; actionMessage?: string; busy?: boolean; onTaskTypeChange?: (value: string) => void; onStateChange?: (value: string) => void; onAction?: (action: components["schemas"]["SupportApprovalAction"]) => void }) {
  return <SupportPage title="승인·감사함" description="개인정보 열람, 긴급 접근과 업무 승인 lineage를 검토합니다."><ScreenGate status={status} loadingLabel="승인 작업을 불러오는 중"><FilterToolbar><SelectField label="작업 유형" value={taskType} onValueChange={onTaskTypeChange ?? (() => undefined)}><option value="">전체 유형</option><option value="DATA_ACCESS_GRANT">개인정보 열람</option><option value="BREAK_GLASS">긴급 접근</option><option value="SUPPORT_ACTION">주문 조치</option><option value="COMPENSATION">보상</option><option value="PROFILE_CHANGE">정보 변경</option></SelectField><SelectField label="상태" value={state} onValueChange={onStateChange ?? (() => undefined)}><option value="">전체 상태</option><option value="APPROVAL_PENDING">승인 대기</option><option value="APPROVED">승인</option><option value="DENIED">거부</option><option value="STALE">버전 만료</option><option value="REASSIGNMENT_REQUIRED">재배정 필요</option></SelectField></FilterToolbar>{actionMessage ? <p className="bf-support-action-feedback" role="status">{actionMessage}</p> : null}<div className="bf-support-master-detail"><Surface title="승인 목록"><WorkspaceDataTable caption="승인 작업" columns={[{ key: "type", label: "유형" }, { key: "case", label: "Case" }, { key: "state", label: "상태" }, { key: "updated", label: "제출 시각" }, { key: "action", label: "작업", align: "end" }]} rows={(page?.items ?? []).map((item) => ({ id: item.resourceId, selected: detail?.task.resourceId === item.resourceId, cells: { type: item.taskType, case: compact(item.caseId), state: <StatusText tone={statusTone(item.state)}>{item.state}</StatusText>, updated: dateTime(item.updatedAt), action: <ButtonLink size="sm" variant="secondary" to={`/support/approvals/${item.taskType}/${item.resourceId}`}>상세</ButtonLink> } }))} /></Surface><Surface title="요청 상세">{detail ? <><FeedbackState kind="empty" title={detail.task.state} description={`${detail.task.taskType} · v${detail.task.version}`} /><div className="bf-support-action-row">{detail.task.allowedActions.map((action) => <Button key={action} size="sm" loading={busy} disabled={!onAction || (detail.task.taskType !== "DATA_ACCESS_GRANT" && action !== "REVIEW")} variant={action === "APPROVE" ? "brand" : "secondary"} onClick={() => onAction?.(action)}>{action}</Button>)}</div><h3>감사 로그</h3><EventTimeline items={(timeline?.items ?? []).map((item) => ({ id: item.eventId, title: item.eventType, occurredAt: dateTime(item.occurredAt), description: item.state }))} /></> : <EmptyState title="승인 작업을 선택해 주세요" description="상세와 감사 lineage가 이 영역에 표시됩니다." />}</Surface></div></ScreenGate></SupportPage>;
}

export const supportScreenIcons = { queue: Headset, intake: MessageSquarePlus, detail: FileSearch, verification: BadgeCheck, order: ShoppingBag, compensation: BadgeDollarSign, profile: ContactRound, approvals: ShieldCheck };
