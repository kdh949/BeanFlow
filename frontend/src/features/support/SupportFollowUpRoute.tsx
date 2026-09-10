import { useCallback, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { operationsApi } from "../../api/consoleClient";
import { unwrap } from "../../api/client";
import { Button, ButtonLink, EmptyState, LoadingState, PageHeading, Tab, TabList, TabPanel, Tabs } from "../../design-system";
import { compactId } from "../../lib/format";
import { ErrorState, StatusText } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
import { SupportCompensationWorkspace } from "./SupportCompensationWorkspace";
import { SupportOrderActionWorkspace } from "./SupportOrderActionWorkspace";
import { SupportVerificationPanel } from "./SupportVerificationPanel";
import { SupportTimelinePanel } from "./SupportTimelinePanel";

type Timeline = components["schemas"]["SupportTimelinePage"];

/** Runtime route: load the authorized case and its timeline without inventing unavailable follow-up lists. */
export function SupportFollowUpRoute() {
  const [params] = useSearchParams();
  const caseId = params.get("caseId")?.trim();
  return <div className="console-page support-follow-up-page">
    <PageHeading title="상담 후속 업무" action={caseId ? <ButtonLink to={`/support?caseId=${encodeURIComponent(caseId)}`} variant="secondary">상담 처리로 돌아가기</ButtonLink> : undefined} />
    {caseId ? <CaseHistory key={caseId} caseId={caseId} requestId={params.get("requestId") ?? undefined} compensationId={params.get("compensationId") ?? undefined} incidentId={params.get("incidentId") ?? undefined} /> : <EmptyState title="상담 건을 먼저 열어 주세요" description="고객지원에서 상담 건을 열고 상담 후속 업무를 선택해 주세요." action={<ButtonLink to="/support">상담 건 열기</ButtonLink>} />}
  </div>;
}

function CaseHistory({ caseId, requestId, compensationId, incidentId }: { caseId: string; requestId?: string; compensationId?: string; incidentId?: string }) {
  const [workspace, setWorkspace] = useState(compensationId || incidentId ? "compensation" : requestId ? "orders" : "history");
  const [verification, setVerification] = useState<components["schemas"]["VerificationSessionResource"] | null>(null);
  const { state, reload } = useResource(useCallback(async () => {
    const [caseResponse, timelineResponse] = await Promise.all([
      operationsApi.GET("/support/cases/{caseId}", { params: { path: { caseId } } }),
      operationsApi.GET("/support/cases/{caseId}/timeline", { params: { path: { caseId }, query: { limit: 50 } } }),
    ]);
    return { supportCase: unwrap(caseResponse), timeline: unwrap(timelineResponse) };
  }, [caseId]));
  const [extended, setExtended] = useState<Timeline | null>(null);
  const [moreError, setMoreError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const inFlight = useRef(false);
  const timeline = extended ?? (state.status === "ready" ? state.value.timeline : null);
  async function loadMore() {
    if (!timeline?.nextCursor || inFlight.current) return;
    inFlight.current = true; setLoadingMore(true); setMoreError(null);
    try {
      const page = unwrap(await operationsApi.GET("/support/cases/{caseId}/timeline", { params: { path: { caseId }, query: { cursor: timeline.nextCursor, limit: 50 } } }));
      const knownIds = new Set(timeline.items.map((item) => item.itemId));
      setExtended({ items: [...timeline.items, ...page.items.filter((item) => !knownIds.has(item.itemId))], nextCursor: page.nextCursor });
    } catch (error) { setMoreError(error); }
    finally { inFlight.current = false; setLoadingMore(false); }
  }
  if (state.status === "loading") return <LoadingState label="상담 건과 이력을 불러오는 중" />;
  if (state.status === "failed") return <ErrorState error={state.error} retry={reload} />;
  const { supportCase } = state.value;
  return <>
    <section className="surface-card follow-up-case-summary" aria-label="현재 상담 건">
      <div><strong>상담 {compactId(supportCase.caseId)}</strong><p className="support-case-reference">상담 ID {supportCase.caseId}</p></div>
      <div><span className="context-label">담당자</span><strong>{compactId(supportCase.assigneeId)}</strong></div>
      <StatusText state={supportCase.state} />
    </section>
    <Tabs value={workspace} onValueChange={setWorkspace}><TabList label="상담 후속 업무 선택"><Tab value="history">상담 이력</Tab><Tab value="orders">주문 변경</Tab><Tab value="compensation">고객 보상</Tab></TabList>
    <TabPanel value="history"><ButtonLink variant="secondary" to={`/support/cases/${caseId}`}>상담 상태·담당자·기록 관리</ButtonLink>
    <SupportTimelinePanel timeline={timeline} />
    {moreError ? <ErrorState error={moreError} retry={() => void loadMore()} /> : null}
    {timeline?.nextCursor ? <Button variant="secondary" loading={loadingMore} onClick={() => void loadMore()}>이력 더 보기</Button> : null}
    </TabPanel><TabPanel value="orders"><div className="management-workspace"><SupportVerificationPanel initialActionScope="SUPPORT_ACTION" caseId={caseId} links={supportCase.subjectLinks} disabled={["RESOLVED", "CLOSED"].includes(supportCase.state)} onChange={setVerification} /><SupportOrderActionWorkspace supportCase={supportCase} verification={verification} initialRequestId={requestId} /></div></TabPanel><TabPanel value="compensation"><div className="management-workspace"><SupportVerificationPanel initialActionScope="SUPPORT_ACTION" caseId={caseId} links={supportCase.subjectLinks} disabled={["RESOLVED", "CLOSED"].includes(supportCase.state)} onChange={setVerification} /><SupportCompensationWorkspace supportCase={supportCase} verification={verification} initialCompensationId={compensationId} initialIncidentId={incidentId} /></div></TabPanel></Tabs>
  </>;
}
