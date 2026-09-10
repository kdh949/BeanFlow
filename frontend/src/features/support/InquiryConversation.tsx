import { useState, type ReactNode } from "react";
import type { components } from "../../api/schema";
import { Button, ButtonLink, EmptyState, InlineNotice, TextAreaField } from "../../design-system";
import { ErrorState, StatusText } from "../../presentation/shared";
import { fullDateTime } from "../../lib/format";
import { useSupportCommand } from "./useSupportCommand";
export type InquiryDetail = components["schemas"]["CustomerInquiryDetail"];
export type InquirySummary = components["schemas"]["CustomerInquirySummary"];
export const inquiryStateLabels: Record<InquirySummary["state"], string> = { RECEIVED: "접수됨", OPEN: "상담원 배정", IN_PROGRESS: "진행 중", WAITING: "확인 대기", RESOLVED: "처리 완료", CLOSED: "종료" };
export const inquiryCategoryLabels: Record<InquirySummary["category"], string> = { ORDER_STATUS: "주문 상태", PICKUP_RESCHEDULE: "픽업 시간", ORDER_CANCELLATION: "주문 취소", PAYMENT_OR_REFUND: "결제·환불", COUPON_OR_POINT: "쿠폰·포인트", CUSTOMER_PROFILE: "내 정보", DELIVERY_STATUS: "배달 상태", ACCOUNT_RECOVERY: "계정 이용", PRIVACY: "개인정보", SAFETY: "안전", OTHER: "기타 문의" };
export const inquiryContentGuidance = "문의 내용만 작성해 주세요. 전화번호·이메일·주소·계좌·카드 번호·비밀번호·인증번호는 입력하지 마세요. 정보 확인과 정정은 상담원의 안내에 따라 별도로 진행합니다.";

/** Shared public conversation. Only the public inquiry DTO reaches this component. */
export function InquiryConversation({ detail, staff = false, canReply, send, refresh, refreshing, paging }: { detail: InquiryDetail; staff?: boolean; canReply: boolean; send: (key: string, content: string) => Promise<unknown>; refresh: () => void; refreshing: boolean; paging: (disabled: boolean) => ReactNode }) {
  const [content, setContent] = useState("");
  const [sent, setSent] = useState(false);
  const command = useSupportCommand(() => {});
  const locked = command.busy || command.pending;
  const inquiry = detail.inquiry;
  function submit() {
    if (locked || !canReply || refreshing || !content.trim()) return;
    const body = content.trim(); setSent(false);
    command.submit(JSON.stringify({ id: inquiry.inquiryId, version: inquiry.version, body }), key => send(key, body), () => { setContent(""); setSent(true); refresh(); });
  }
  return <section className="management-workspace" aria-label="공개 문의 대화">
    <div className="surface-card management-card"><h2 className="inquiry-heading">{inquiry.title}</h2><p><StatusText state={inquiry.state} label={inquiryStateLabels[inquiry.state]} /> · {inquiryCategoryLabels[inquiry.category]}</p><p>{fullDateTime.format(new Date(inquiry.createdAt))}</p>{inquiry.orderReference ? <p>관련 주문 {inquiry.orderReference}</p> : null}<Button variant="secondary" disabled={locked || refreshing} onClick={refresh}>문의 새로고침</Button></div>
    {sent ? <InlineNotice tone="info" announce="polite" title="메시지를 보냈습니다" description="이 문의에서 답변과 처리 상태를 계속 확인할 수 있습니다." /> : null}
    {command.failure ? <ErrorState error={command.failure} /> : null}
    {command.pending ? <InlineNotice tone="warning" title="전송 결과를 확인하지 못했습니다" description="같은 내용으로 전송 결과를 확인합니다." action={<Button loading={command.busy} onClick={() => void command.retry()}>같은 요청 결과 확인</Button>} /> : null}
    {detail.messages.length ? <div className="management-workspace">{detail.messages.map(message => <article className="surface-card management-card" key={message.id}><h3>{message.author === "SUPPORT" ? "상담원 답변" : "고객 문의"}</h3><p>{fullDateTime.format(new Date(message.createdAt))}</p><p className="inquiry-message-content">{message.content}</p></article>)}</div> : <EmptyState title="공개 메시지가 없습니다" description="문의 상태를 새로고침해 주세요." />}
    {paging(locked || refreshing)}
    {canReply ? <form className="surface-card management-card management-workspace" onSubmit={event => { event.preventDefault(); submit(); }}><TextAreaField label={staff ? "공개 답변" : "추가 문의 내용"} description={inquiryContentGuidance} value={content} onValueChange={setContent} maxLength={2000} required disabled={locked || refreshing} size={staff ? "md" : "lg"} />{staff ? <p>이 답변은 고객에게 공개됩니다. 내부 검토 내용은 연결된 상담의 내부 노트에 기록해 주세요.</p> : null}<Button type="submit" loading={command.busy} disabled={locked || refreshing || !content.trim()}>{staff ? "고객에게 답변 보내기" : "추가 문의 보내기"}</Button></form> : detail.canReply ? <InlineNotice tone="info" title="현재 담당 상담원만 답변할 수 있습니다" description="미인수 문의는 인수 후 답변할 수 있습니다. 기존 상담은 담당자와 권한을 확인해 주세요." /> : <InlineNotice tone="info" title="처리된 문의입니다" description="추가로 도움이 필요하면 새 문의를 접수해 주세요." action={staff ? undefined : <ButtonLink to="/app/support/new">새 문의 접수</ButtonLink>} />}
  </section>;
}

export function InquiryList({ items, staff = false }: { items: InquirySummary[]; staff?: boolean }) {
  return items.length ? <div className="management-workspace">{items.map(item => <article className="surface-card management-card" key={item.inquiryId}><p><StatusText state={item.state} label={inquiryStateLabels[item.state]} /> · {inquiryCategoryLabels[item.category]}</p><ButtonLink variant="ghost" to={`${staff ? "/support/inquiries" : "/app/support"}/${item.inquiryId}`}>{item.title}</ButtonLink><p>{fullDateTime.format(new Date(item.createdAt))}</p></article>)}</div> : <EmptyState title={staff ? "대기 중인 문의가 없습니다" : "접수한 문의가 없습니다"} description={staff ? "다른 접수 범위를 선택하거나 새로고침해 주세요." : "궁금한 점을 남기면 이곳에서 답변을 확인할 수 있습니다."} />;
}
export function InquiryPager({ cursors, next, setCursors, disabled = false }: { cursors: (string | undefined)[]; next: string | null; setCursors: (values: (string | undefined)[]) => void; disabled?: boolean }) {
  return <div className="button-row"><Button variant="secondary" disabled={disabled || cursors.length === 1} onClick={() => setCursors(cursors.slice(0, -1))}>이전 목록</Button><Button variant="secondary" disabled={disabled || !next} onClick={() => { if (next) setCursors([...cursors, next]); }}>다음 목록</Button></div>;
}
