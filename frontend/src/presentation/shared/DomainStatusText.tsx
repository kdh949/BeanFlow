import { StatusText as VisualStatusText, type StatusTextTone } from "../../design-system";

export type DomainStatusTextProps = { state: string; label?: string };

const labels: Record<string, string> = {
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

const uncertainty = new Set(["UNKNOWN", "RECONCILING", "MANUAL_REVIEW"]);
const failure = new Set(["FAILED", "CANCELLED", "REJECTED", "EXPIRED"]);

/** Owns BeanFlow domain-state copy while delegating only visual tone to the design system. */
export function DomainStatusText({ state, label }: DomainStatusTextProps) {
  const tone: StatusTextTone = uncertainty.has(state) ? "uncertain" : failure.has(state) ? "danger" : "neutral";
  return <VisualStatusText tone={tone}>{label ?? labels[state] ?? state}</VisualStatusText>;
}
