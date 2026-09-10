import { useCallback, useState } from "react";
import { useParams } from "react-router";
import { operationsApi } from "../../api/consoleClient";
import { unwrap } from "../../api/client";
import { Button, ButtonLink, InlineNotice, LoadingState, PageHeading, SelectField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
import { useSupportCommand } from "./useSupportCommand";
import { InquiryConversation, InquiryList, InquiryPager } from "./InquiryConversation";

/** Permission-gated native customer intake queue. */
export function SupportInquiryDirectoryPage() {
  const [unclaimed, setUnclaimed] = useState(true);
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors.at(-1);
  const resource = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/inquiries", { params: { query: { unclaimed, cursor } } })), [unclaimed, cursor]));
  return <div className="management-workspace"><PageHeading title="고객 문의 접수함" /><SelectField label="접수 범위" value={String(unclaimed)} onValueChange={value => { setUnclaimed(value === "true"); setCursors([undefined]); }}><option value="true">미인수 문의</option><option value="false">전체 문의</option></SelectField>{resource.state.status === "loading" ? <LoadingState label="접수함을 불러오는 중입니다" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <><InquiryList staff items={resource.state.value.items} /><InquiryPager cursors={cursors} next={resource.state.value.nextCursor} setCursors={setCursors} /><Button variant="secondary" onClick={resource.reload}>접수함 새로고침</Button></>}</div>;
}

/** Claims one intake into a real assigned Case and sends explicitly public replies. */
export function SupportInquiryDetailPage() {
  const { inquiryId = "" } = useParams();
  return <SupportInquiryDetail key={inquiryId} inquiryId={inquiryId} />;
}
function SupportInquiryDetail({ inquiryId }: { inquiryId: string }) {
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const [claimedId, setClaimedId] = useState<string | null>(null);
  const cursor = cursors.at(-1);
  const resource = useResource(useCallback(async () => unwrap(await operationsApi.GET("/support/inquiries/{inquiryId}", { params: { path: { inquiryId }, query: { messageCursor: cursor } } })), [inquiryId, cursor]));
  const claim = useSupportCommand(() => {});
  const value = resource.state.status === "ready" ? resource.state.value : null;
  const caseId = value?.caseId || claimedId;
  const locked = claim.busy || claim.pending;
  function take() {
    if (!value?.canClaim || locked || claimedId || resource.refreshing) return;
    const body = { expectedVersion: value.detail.inquiry.version };
    claim.submit(JSON.stringify({ inquiryId, body }), async key => { const result = unwrap(await operationsApi.POST("/support/inquiries/{inquiryId}/claims", { params: { path: { inquiryId }, header: { "Idempotency-Key": key } }, body })); setClaimedId(result.caseId); }, resource.refresh);
  }
  return <div className="management-workspace"><PageHeading title="고객 문의" action={<ButtonLink variant="ghost" to="/support/inquiries">접수함</ButtonLink>} />{caseId ? <ButtonLink variant="secondary" to={`/support/cases/${caseId}`}>연결된 상담 관리</ButtonLink> : null}{claim.failure ? <ErrorState error={claim.failure} /> : null}{claim.pending ? <InlineNotice tone="warning" title="인수 결과를 확인하지 못했습니다" description="같은 요청으로 연결된 상담을 확인합니다." action={<Button loading={claim.busy} onClick={() => void claim.retry()}>같은 요청 결과 확인</Button>} /> : null}{resource.state.status === "loading" ? <LoadingState label="문의 내용을 불러오는 중입니다" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <>
    {value?.canClaim && !claimedId ? <InlineNotice tone="info" title="상담원이 아직 인수하지 않았습니다" description="인수하면 본인이 담당자인 상담이 만들어지고 문의 고객과 주문이 연결됩니다." action={<Button loading={claim.busy} disabled={locked || resource.refreshing} onClick={take}>인수하여 상담 열기</Button>} /> : null}
    <InquiryConversation staff detail={resource.state.value.detail} canReply={resource.state.value.canReply && !locked} refreshing={resource.refreshing || locked} refresh={resource.refresh} send={async (key, content) => { if (!value || value.caseVersion === null) throw new Error("Inquiry case context is unavailable"); return unwrap(await operationsApi.POST("/support/inquiries/{inquiryId}/messages", { params: { path: { inquiryId }, header: { "Idempotency-Key": key } }, body: { expectedVersion: value.detail.inquiry.version, expectedCaseVersion: value.caseVersion, content } })); }} paging={disabled => <InquiryPager disabled={disabled || locked} cursors={cursors} next={value?.detail.nextMessageCursor ?? null} setCursors={setCursors} />} />
  </>}</div>;
}
