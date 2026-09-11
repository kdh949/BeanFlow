import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, InlineNotice, LoadingState } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { profilePurposes } from "../../lib/supportProfilePayload";
import { orderActionLabels } from "../../lib/supportOrderPayload";
import { useResource } from "../shared/useResource";
import { caseCategoryLabels } from "./supportCaseLabels";
import { verificationPurposeLabels } from "./supportSecurityLabels";
type Kind = components["schemas"]["SupportWorkKind"];
export type SupportWorkSelection = components["schemas"]["SupportWorkItem"];
export const supportWorkLabels: Record<Kind, string> = { VERIFICATION: "본인확인", DATA_ACCESS: "열람 승인", ORDER_ACTION: "주문 변경", COMPENSATION: "보상", PROFILE_CHANGE: "정보 정정", BREAK_GLASS: "긴급 열람" };
const purposes: Record<string, string> = { ...verificationPurposeLabels, ...orderActionLabels, ...Object.fromEntries(Object.entries(profilePurposes).map(([key, value]) => [key, value.label])), POST_ACCEPTANCE_RESOLUTION: "수락 후 주문 조정", POINT: "포인트 보상", POINT_CREDIT: "포인트 보상", COUPON: "쿠폰 보상" };
const states: Record<string, string> = { PENDING: "본인확인 대기", VERIFIED: "본인확인 완료", LOCKED: "본인확인 잠김", EXPIRED: "기한 만료", REVOKED: "철회됨", REQUESTED: "요청됨", APPROVAL_PENDING: "별도 승인 대기", ACTIVE: "열람 가능", DENIED: "거부됨", CONSUMED: "열람 횟수 소진", AWAITING_APPROVAL: "승인 대기", AWAITING_SUPPORT_MANAGER: "상담 관리자 승인 대기", AWAITING_OPERATIONS: "운영 조사·승인 대기", READY_FOR_EXECUTION: "실행 준비", REASSIGNMENT_REQUIRED: "실행 담당자 재배정 필요", REVISION_REQUIRED: "조건 수정 필요", EXECUTED: "실행 완료", REJECTED: "거절됨", BENEFIT_ISSUED: "보상 지급 완료", NOTIFICATION_RETRY: "알림 접수 재시도 필요", NOTIFICATION_ACCEPTED: "알림 접수 완료", NOTIFICATION_SKIPPED: "알림 제외", REVIEW_PENDING: "사후 검토 대기", REVIEWED: "사후 검토 완료", CANCELLED: "취소됨", FAILED: "실패", UNKNOWN: "처리 결과 확인 중", APPLYING: "정정 반영 중", APPLIED: "정정 반영 완료" };
/** Finds persisted requests through their existing authorization checks; selecting always reloads current detail. */
export function SupportWorkPicker({ kind, caseId, onSelect, disabled = false }: {
  kind: Kind;
  /** Omit only for a reviewer searching requests across cases. */
  caseId?: string;
  onSelect: (item: SupportWorkSelection) => void;
  /** Locks request changes while a command or sensitive reveal remains unresolved. */
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  return <section className="management-workspace" aria-label={`기존 ${supportWorkLabels[kind]} 요청 선택`}><Button type="button" variant="secondary" disabled={disabled} aria-expanded={open} onClick={() => setOpen(value => !value)}>{open ? "요청 목록 닫기" : `기존 ${supportWorkLabels[kind]} 요청 찾기`}</Button>{open ? <WorkList key={`${kind}:${caseId ?? "all"}`} kind={kind} caseId={caseId} disabled={disabled} onSelect={item => { if (!disabled) { onSelect(item); setOpen(false); } }} /> : null}</section>;
}
function WorkList({ kind, caseId, onSelect, disabled }: { kind: Kind; caseId?: string; onSelect: (item: SupportWorkSelection) => void; disabled: boolean }) {
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const cursor = cursors.at(-1);
  const resource = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/work-items", { params: { query: { kind, caseId, cursor, limit: 20 } } })), [kind, caseId, cursor]));
  return <fieldset className="catalog-fieldset management-workspace" disabled={disabled}><legend>{supportWorkLabels[kind]} 요청 목록</legend><InlineNotice title="기존 요청의 현재 상태를 확인합니다" description="요청을 고르면 상세를 다시 조회합니다. 이 목록을 여는 것만으로 승인이나 실행이 처리되지는 않습니다." />{resource.state.status === "loading" ? <LoadingState label="기존 업무 요청을 확인하는 중" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <>{resource.state.value.items.length ? <div className="management-card-grid">{resource.state.value.items.map(item => <article key={item.requestId} className="surface-card management-card management-workspace"><p><strong>{purposes[item.purpose] ?? supportWorkLabels[item.kind]}</strong></p><StatusText state={item.state} label={states[item.state] ?? "현재 상태 확인 필요"} /><dl className="detail-list"><div><dt>요청 시각</dt><dd>{fullDateTime.format(new Date(item.createdAt))}</dd></div><div><dt>상담</dt><dd>{caseCategoryLabels[item.caseCategory]} · {fullDateTime.format(new Date(item.caseOpenedAt))} 접수</dd></div>{item.expiresAt ? <div><dt>관련 인증·요청 기한</dt><dd>{fullDateTime.format(new Date(item.expiresAt))}</dd></div> : null}</dl><Button type="button" onClick={() => onSelect(item)}>이 요청 열기</Button></article>)}</div> : <EmptyState title="현재 조회 구간에 볼 수 있는 요청이 없습니다" description={resource.state.value.nextCursor ? "다음 조회 구간에 다른 요청이 있을 수 있습니다." : "다른 상담을 확인하거나 새 요청을 시작할 수 있습니다."} />}<div className="button-row"><Button type="button" variant="secondary" disabled={disabled || cursors.length === 1} onClick={() => setCursors(value => value.slice(0, -1))}>이전 요청</Button><Button type="button" variant="secondary" disabled={disabled || !resource.state.value.nextCursor} onClick={() => { const next = resource.state.status === "ready" ? resource.state.value.nextCursor : null; if (next) setCursors(value => [...value, next]); }}>다음 요청</Button><Button type="button" variant="ghost" onClick={resource.reload}>요청 목록 새로고침</Button></div></>}</fieldset>;
}
