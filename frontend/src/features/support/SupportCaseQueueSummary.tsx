import { useCallback } from "react";
import { operationsApi } from "../../api/consoleClient";
import { unwrap } from "../../api/client";
import { Button, LoadingState } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
/** Server counts for the signed-in assignee; terminal cases are excluded by the server. */
export function SupportCaseQueueSummary() {
  const { state, reload } = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/case-queue/summary")), []));
  return <section className="surface-card management-card management-workspace" aria-label="내 상담 현황"><h2>내 상담 현황</h2><p>현재 나에게 배정된 상담입니다. 해결·종료된 상담은 진행 중 건수에서 제외합니다.</p>{state.status === "loading" ? <LoadingState label="내 상담 현황을 확인하는 중" /> : state.status === "failed" ? <ErrorState error={state.error} retry={reload} /> : <><div className="management-card-grid"><strong>진행 중 {state.value.active}건</strong><span>신규 {state.value.open}건</span><span>처리 중 {state.value.inProgress}건</span><span>대기 {state.value.waiting}건</span><strong>긴급 {state.value.urgent}건</strong></div><Button variant="secondary" onClick={reload}>현황 새로고침</Button></>}</section>;
}
