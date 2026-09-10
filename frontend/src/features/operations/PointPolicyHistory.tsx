import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { operationsApi } from "../../api/consoleClient";
import { Button, EmptyState, LoadingState, SelectField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useResource } from "../shared/useResource";

type Version = components["schemas"]["OrdinaryPointAccrualPolicyVersion"];
/** Audited global or store history; loading it is an explicit operator action. */
export function PointPolicyHistory({ storeId }: { storeId?: string }) {
  const [reason, setReason] = useState("");
  const [request, setRequest] = useState<{ reason: string; cursor?: string } | null>(null);
  const [cursors, setCursors] = useState<Array<string | undefined>>([undefined]);
  const history = useResource(useCallback(async () => {
    if (!request) return null;
    const header = { "X-Access-Reason": request.reason }; const query = { cursor: request.cursor, limit: 20 };
    return storeId ? unwrap(await operationsApi.GET("/operations/policies/ordinary-point-accrual/stores/{storeId}/versions", { params: { path: { storeId }, header, query } })) : unwrap(await operationsApi.GET("/operations/policies/ordinary-point-accrual/global/versions", { params: { header, query } }));
  }, [storeId, request]));
  return <section className="management-workspace"><h3>{storeId ? "매장 포인트 변경 이력" : "공통 포인트 변경 이력"}</h3>
    <div className="button-row"><SelectField label="포인트 이력 조회 사유" value={reason} onValueChange={setReason}><option value="">조회 목적 선택</option><option value="POLICY_AUDIT_REVIEW">정책 변경 감사</option></SelectField><Button variant="secondary" disabled={!reason} onClick={() => { setCursors([undefined]); setRequest({ reason }); }}>포인트 변경 이력 조회</Button></div>
    {history.state.status === "loading" ? <LoadingState label="포인트 변경 이력을 불러오는 중" /> : history.state.status === "failed" ? <ErrorState error={history.state.error} retry={history.reload} /> : history.state.value ? <>
      {history.state.value.items.length ? <div className="management-card-grid">{history.state.value.items.map(version => <PolicyVersionSummary key={version.policyVersionId} version={version} />)}</div> : <EmptyState title="변경 이력이 없습니다" description="이 범위에 저장된 정책 버전이 없습니다." />}
      <div className="button-row"><Button variant="ghost" disabled={cursors.length < 2} onClick={() => { const next = cursors.slice(0, -1); setCursors(next); setRequest({ reason: request!.reason, cursor: next.at(-1) }); }}>이전 포인트 이력</Button><Button variant="secondary" disabled={!history.state.value.page.nextCursor} onClick={() => { if (history.state.status === "ready" && history.state.value?.page.nextCursor) { const cursor = history.state.value.page.nextCursor; setCursors(value => [...value, cursor]); setRequest({ reason: request!.reason, cursor }); } }}>다음 포인트 이력</Button></div>
    </> : null}
  </section>;
}
export function PolicyVersionSummary({ version }: { version: Version }) {
  return <article className="surface-card management-card"><h4>버전 {version.policyVersionId}</h4><p>{fullDateTime.format(new Date(version.effectiveAt))}</p><p>{version.state === "INHERIT_GLOBAL" ? "공통 정책 상속" : "전용 정책"}</p>
    {version.state === "OVERRIDE" ? <dl className="detail-list"><div><dt>적립률</dt><dd>{version.accrualRateBps === undefined ? "확인할 수 없음" : `${(version.accrualRateBps / 100).toFixed(2)}%`}</dd></div><div><dt>반올림</dt><dd>{version.roundingMode === "FLOOR" ? "버림" : version.roundingMode === "HALF_UP" ? "반올림" : "확인할 수 없음"}</dd></div><div><dt>비용 주체</dt><dd>{version.issuerType} · {version.issuerReference}</dd></div><div><dt>유효기간</dt><dd>{version.validityDays}일 · {version.expiryRule === "EXACT_DURATION_FROM_COMPLETION" ? "정확한 시간" : version.expiryRule === "SEOUL_CALENDAR_DAYS_FROM_COMPLETION" ? "서울 달력일" : "확인할 수 없음"}</dd></div></dl> : <p>이 버전은 공통 정책 값을 복사하지 않습니다. 주문 시점의 공통 정책을 적용합니다.</p>}
    <p>{version.reason}</p><p className="support-case-reference">변경 담당: {version.actorReference}</p>
  </article>;
}
