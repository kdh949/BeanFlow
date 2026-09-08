import { StatusText as VisualStatusText, type StatusTextTone } from "../../design-system";

export type DomainStatusTextProps = { state: string; label?: string; domain?: "account" | "dispute" | "grant" | "brand" | "catalog" };

const labels: Record<string, string> = {
  ACTIVE: "활성",
  OWNER: "점주", STAFF: "직원", INITIAL_PASSWORD: "초기 비밀번호 변경 필요",
  OPEN: "접수", WAITING: "응답 대기", RESOLVED: "해결됨", CLOSED: "종료됨",
  CUSTOMER: "고객", STORE: "매장", RIDER: "외부 배달원", DELIVERY: "배송", ORDER: "주문",
  NONE: "미확인", UNVERIFIED: "미확인", BASIC: "기본 확인", ENHANCED: "강화 확인", VERIFIED: "확인 완료",
  ISSUED: "발급됨", LOCKED: "잠김", AMBIGUOUS: "후보 확인 필요",
  SENSITIVE: "민감 정보", HIGHLY_SENSITIVE: "고위험 정보",
  SOLD_OUT: "품절",
  ARCHIVED: "보관됨",
  AVAILABLE: "판매 가능",
  LOW: "재고 부족",
  DEPLETED: "재고 없음",
  PAUSED: "일시 중지",
  FILED: "접수됨",
  UNDER_REVIEW: "검토 중",
  WITHDRAWN: "철회됨",
  APPROVAL_REQUIRED: "승인 필요",
  UNASSIGNED: "담당자 미배정",
  SCHEDULED: "예약됨",
  ACTION_REQUIRED: "조치 필요",
  CONFIRMED: "확인 완료",
  MISMATCH: "금액 불일치",
  CONSISTENT: "일치",
  INCOMPLETE: "확인 필요",
  ASSIGNED: "담당자 배정",
  RECORDED: "기록 완료",
  PENDING: "대기 중",
  IN_PROGRESS: "진행 중",
  READY_FOR_EXECUTION: "실행 준비",
  PARTIALLY_RESOLVED: "일부 해결",
  BENEFIT_ISSUED: "보상 지급 완료",
  REVIEW_PENDING: "검토 대기",
  PENDING_PAYMENT: "결제 대기",
  READY: "준비 완료",
  APPROVING: "승인 중",
  CONFIRMING: "승인 중",
  APPROVED: "결제 완료",
  SUCCEEDED: "완료",
  PAID: "결제 완료",
  ACCEPTED: "주문 접수",
  REQUESTED: "요청됨",
  PREPARING: "제조 중",
  PROCESSING: "처리 중",
  RETRY_SCHEDULED: "재시도 예정",
  COMPLETED: "픽업 완료",
  CANCELLED: "취소됨",
  REJECTED: "거절됨",
  EXPIRED: "만료됨",
  UNKNOWN: "확인 중",
  RECONCILING: "복구 중",
  MANUAL_REVIEW: "확인 필요",
  FAILED: "실패",
  NOT_REQUIRED: "해당 없음",
};

const domainLabels: Record<NonNullable<DomainStatusTextProps["domain"]>, Record<string, string>> = {
  account: { ACTIVE: "사용 가능", DISABLED: "사용 중지" },
  dispute: { ACCEPTED: "인정", REJECTED: "기각" },
  grant: { ACTIVE: "열람 승인", APPROVAL_PENDING: "열람 승인 대기" },
  brand: { ACTIVE: "운영 중" },
  catalog: { ACTIVE: "판매 중" },
};

const uncertainty = new Set(["UNKNOWN", "RECONCILING", "MANUAL_REVIEW", "INCOMPLETE", "REVIEW_PENDING"]);
const failure = new Set(["FAILED", "CANCELLED", "REJECTED", "EXPIRED"]);

/** Owns BeanFlow domain-state copy while delegating only visual tone to the design system. */
export function DomainStatusText({ state, label, domain }: DomainStatusTextProps) {
  const tone: StatusTextTone = uncertainty.has(state) ? "uncertain" : failure.has(state) ? "danger" : "neutral";
  return <VisualStatusText tone={tone}>{label ?? (domain ? domainLabels[domain][state] : undefined) ?? labels[state] ?? state}</VisualStatusText>;
}
