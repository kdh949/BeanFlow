export type StatusBadgeProps = {
  /** Server state code. Known transaction states receive Korean labels and semantic tones. */
  state: string;
  /**
   * Overrides the shared label where one code means different things to different
   * lifecycles — `READY` is a prepared pickup on the order board and an unpaid
   * payment on the payment screens. The tone still follows `state`.
   */
  label?: string;
};

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

const tones: Record<string, string> = {
  READY: "success",
  APPROVED: "success",
  SUCCEEDED: "success",
  PAID: "success",
  COMPLETED: "success",
  ACCEPTED: "info",
  REQUESTED: "info",
  PENDING_PAYMENT: "warning",
  PREPARING: "warning",
  PROCESSING: "warning",
  APPROVING: "warning",
  CONFIRMING: "warning",
  RETRY_SCHEDULED: "warning",
  UNKNOWN: "brand",
  RECONCILING: "brand",
  MANUAL_REVIEW: "brand",
  CANCELLED: "danger",
  REJECTED: "danger",
  EXPIRED: "danger",
  FAILED: "danger",
};

/** Domain-aware badge that preserves uncertain transaction states instead of collapsing them. */
export function StatusBadge({ state, label }: StatusBadgeProps) {
  const tone = tones[state] ?? "neutral";
  return (
    <span className={`bf-status bf-status--${tone}`}>
      <span className="bf-status__dot" aria-hidden="true" />
      {label ?? labels[state] ?? state}
    </span>
  );
}
