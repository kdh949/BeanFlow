import type { components } from "../../api/schema";

type TimelineType = components["schemas"]["SupportTimelineType"];
type TimelineState = components["schemas"]["SupportTimelineState"];

const types: Record<TimelineType, { label: string; subject: string }> = {
  CASE_STATE: { label: "상담 상태", subject: "상담" },
  CASE_ASSIGNMENT: { label: "상담 배정", subject: "상담" },
  CASE_INTERACTION: { label: "상담 내용", subject: "상담" },
  CASE_NOTE: { label: "상담 메모", subject: "상담 메모" },
  SUBJECT_LINK: { label: "대상 연결", subject: "상담 대상" },
  ORDER_STATE: { label: "주문 상태", subject: "주문" },
  PAYMENT_STATE: { label: "결제 상태", subject: "결제" },
  REFUND_STATE: { label: "환불 상태", subject: "환불" },
  POINT_RESERVATION: { label: "포인트 예약", subject: "포인트" },
  COUPON_RESERVATION: { label: "쿠폰 예약", subject: "쿠폰" },
  PICKUP_RESERVATION: { label: "픽업 예약", subject: "픽업" },
  SETTLEMENT_ITEM: { label: "정산 명세", subject: "정산 명세" },
  SETTLEMENT_ADJUSTMENT: { label: "정산 조정", subject: "정산 조정" },
  NOTIFICATION_DELIVERY: { label: "알림 전달", subject: "알림" },
  OPERATION_AUDIT: { label: "업무 감사", subject: "업무 감사" },
};

const states: Record<TimelineState, string> = {
  OPEN: "접수", PENDING_CUSTOMER: "고객 응답 대기", PENDING_STORE: "매장 응답 대기", ESCALATED: "상위 담당자 이관",
  RESOLVED: "해결", CLOSED: "종료", ASSIGNED: "담당자 배정", INBOUND: "수신 기록", OUTBOUND: "발신 기록", INTERNAL: "내부 기록",
  RECORDED: "기록", LINKED: "연결", UNLINKED: "연결 해제", PENDING_PAYMENT: "결제 대기", PAID: "결제 완료",
  ACCEPTED: "접수", PREPARING: "제조 중", READY: "준비 완료", COMPLETED: "완료", REJECTED: "거절", EXPIRED: "만료", CANCELLED: "취소",
  APPROVING: "승인 중", APPROVED: "승인 완료", FAILED: "실패", UNKNOWN: "결과 확인 중", RECONCILING: "결과 재확인 중",
  MANUAL_REVIEW: "운영팀 확인 필요", REQUESTED: "요청", PROCESSING: "처리 중", SUCCEEDED: "완료",
  RESERVED: "예약", USED: "사용", RELEASED: "예약 해제", CONFIRMED: "확정", ITEM_CREATED: "생성", ADJUSTMENT_RECORDED: "기록",
  PENDING: "대기", RETRY_SCHEDULED: "재시도 예정",
};

const contextualStates: Partial<Record<TimelineType, Partial<Record<TimelineState, string>>>> = {
  ORDER_STATE: { COMPLETED: "픽업 완료" },
  PICKUP_RESERVATION: { CONFIRMED: "예약 확정" },
  NOTIFICATION_DELIVERY: { ACCEPTED: "발송 접수", PENDING: "발송 대기" },
};

/** Translate server facts, never parse or render the server's machine-oriented summary. */
export function supportTimelinePresentation(item: { type: TimelineType; state: TimelineState }) {
  const type = Object.hasOwn(types, item.type) ? types[item.type] : undefined;
  const stateLabel = type && Object.hasOwn(states, item.state)
    ? contextualStates[item.type]?.[item.state] ?? states[item.state]
    : "상태 확인 필요";
  return { typeLabel: type?.label ?? "업무 이력", stateLabel, summary: `${type?.subject ?? "이력"} ${stateLabel}` };
}
