import { useCallback, useState } from "react";
import { useParams, useSearchParams } from "react-router";
import type { components } from "../../api/schema";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { unwrap } from "../../api/client";
import { Button, ButtonLink, InlineNotice, LoadingState, PageHeading, SelectField, TextAreaField, TextField } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";
import { useSupportCommand } from "./useSupportCommand";
import { customerInquiryActor } from "./customerInquiryActor";
import { InquiryConversation, InquiryList, InquiryPager, inquiryCategoryLabels, inquiryContentGuidance } from "./InquiryConversation";

/** Authenticated customer's own inquiry directory; independent of operator credentials. */
export function CustomerInquiryDirectoryPage() {
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors.at(-1);
  const resource = useResource(useCallback(async () => unwrap(await customerApi.GET("/me/support-inquiries", { params: { query: { cursor } } })), [cursor]));
  return <div className="customer-page management-workspace"><PageHeading title="내 문의" action={<ButtonLink to="/app/support/new">새 문의 접수</ButtonLink>} /><p>상담원의 답변과 처리 상태를 이곳에서 확인할 수 있어요.</p>{resource.state.status === "loading" ? <LoadingState label="문의를 불러오는 중입니다" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <><InquiryList items={resource.state.value.items} /><InquiryPager cursors={cursors} next={resource.state.value.nextCursor} setCursors={setCursors} /><Button variant="secondary" onClick={resource.reload}>문의함 새로고침</Button></>}</div>;
}

/** Creates an inquiry using an optional customer-owned order selected from order detail. */
export function CustomerInquiryCreatePage() {
  const [query] = useSearchParams();
  const reference = query.get("orderReference") || undefined;
  return <div className="customer-page management-workspace"><PageHeading title="새 문의" action={<ButtonLink variant="ghost" to="/app/support">내 문의</ButtonLink>} /><CustomerInquiryForm key={reference ?? "general"} orderReference={reference} /></div>;
}
function CustomerInquiryForm({ orderReference }: { orderReference?: string }) {
  const [title, setTitle] = useState("");
  const [category, setCategory] = useState<components["schemas"]["CustomerInquiryCategory"]>("PAYMENT_OR_REFUND");
  const [content, setContent] = useState("");
  const [createdId, setCreatedId] = useState<string | null>(null);
  const order = useResource(useCallback(async () => orderReference ? unwrap(await customerApi.GET("/me/orders/{orderReference}", { params: { path: { orderReference } } })) : null, [orderReference]));
  const command = useSupportCommand(`inquiry-create:${orderReference ?? "general"}`, () => {}, customerInquiryActor);
  const locked = command.busy || command.pending || Boolean(createdId);
  function submit() {
    if (locked || order.state.status !== "ready" || !title.trim() || !content.trim()) return;
    const body: components["schemas"]["CreateCustomerInquiryRequest"] = { title: title.trim(), category, content: content.trim(), ...(orderReference ? { orderReference } : {}) };
    command.submit(JSON.stringify(body), async key => { const result = unwrap(await customerApi.POST("/me/support-inquiries", { params: { header: { "Idempotency-Key": key, ...await customerCsrfHeader() } }, body })); setCreatedId(result.inquiryId); }, () => { setContent(""); });
  }
  return <section className="management-workspace">
    {createdId ? <InlineNotice tone="info" title="문의를 접수했습니다" description="상담원이 확인하면 문의함에서 답변과 처리 상태를 볼 수 있습니다." announce="polite" action={<ButtonLink to={`/app/support/${createdId}`}>접수한 문의 보기</ButtonLink>} /> : null}
    {command.failure ? <ErrorState error={command.failure} /> : null}
    {command.pending ? <InlineNotice tone="warning" title="접수 결과를 확인하지 못했습니다" description="같은 요청의 결과를 확인해 주세요. 문의를 다시 만들지 않습니다." action={<Button loading={command.busy} onClick={() => void command.retry()}>같은 요청 결과 확인</Button>} /> : null}
    {order.state.status === "loading" ? <LoadingState label="문의 정보를 준비하는 중입니다" /> : order.state.status === "failed" ? <><ErrorState error={order.state.error} retry={order.reload} /><ButtonLink variant="secondary" to="/app/support/new">주문 연결 없이 문의하기</ButtonLink></> : <form className="surface-card management-card management-workspace" onSubmit={event => { event.preventDefault(); submit(); }}>
      {order.state.value ? <p>관련 주문 {orderReference}</p> : <p>특정 주문에 관한 문의는 <ButtonLink variant="ghost" to="/app/orders">주문 내역</ButtonLink>에서 주문을 선택한 뒤 접수할 수 있어요.</p>}
      <TextField label="문의 제목" value={title} onValueChange={setTitle} required maxLength={100} disabled={locked} size="lg" />
      <SelectField label="문의 유형" value={category} onValueChange={value => setCategory(value as typeof category)} disabled={locked} size="lg">{Object.entries(inquiryCategoryLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
      <TextAreaField label="문의 내용" description={inquiryContentGuidance} value={content} onValueChange={setContent} required maxLength={2000} disabled={locked} size="lg" />
      <Button type="submit" loading={command.busy} disabled={locked || !title.trim() || !content.trim()}>문의 접수</Button>
    </form>}
  </section>;
}

/** Public messages and authoritative status for one owned inquiry. */
export function CustomerInquiryDetailPage() {
  const { inquiryId = "" } = useParams();
  return <CustomerInquiryDetail key={inquiryId} inquiryId={inquiryId} />;
}
function CustomerInquiryDetail({ inquiryId }: { inquiryId: string }) {
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors.at(-1);
  const resource = useResource(useCallback(async () => unwrap(await customerApi.GET("/me/support-inquiries/{inquiryId}", { params: { path: { inquiryId }, query: { messageCursor: cursor } } })), [inquiryId, cursor]));
  return <div className="customer-page management-workspace"><PageHeading title="문의 내용" action={<ButtonLink variant="ghost" to="/app/support">내 문의</ButtonLink>} />{resource.state.status === "loading" ? <LoadingState label="문의를 불러오는 중입니다" /> : resource.state.status === "failed" ? <ErrorState error={resource.state.error} retry={resource.reload} /> : <InquiryConversation detail={resource.state.value} canReply={resource.state.value.canReply} refreshing={resource.refreshing} refresh={resource.refresh} send={async (key, content) => { if (resource.state.status !== "ready") throw new Error("Inquiry context is unavailable"); return unwrap(await customerApi.POST("/me/support-inquiries/{inquiryId}/messages", { params: { path: { inquiryId }, header: { "Idempotency-Key": key, ...await customerCsrfHeader() } }, body: { expectedVersion: resource.state.value.inquiry.version, content } })); }} paging={disabled => <InquiryPager disabled={disabled} cursors={cursors} next={resource.state.status === "ready" ? resource.state.value.nextMessageCursor : null} setCursors={setCursors} />} />}</div>;
}
