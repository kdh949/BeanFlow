import { AlertCircle, CheckCircle2, Clock3, LoaderCircle, RefreshCw } from "lucide-react";
import type { ReactNode } from "react";
import { ApiRequestError } from "../api/client";

export function LoadingState({ label = "불러오는 중" }: { label?: string }) {
  return (
    <div className="state-card" role="status">
      <LoaderCircle className="spin" size={24} />
      <strong>{label}</strong>
      <span>잠시만 기다려 주세요.</span>
    </div>
  );
}

export function EmptyState({ title, description, action }: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="state-card">
      <Clock3 size={25} />
      <strong>{title}</strong>
      <span>{description}</span>
      {action}
    </div>
  );
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const apiError = error instanceof ApiRequestError ? error : null;
  return (
    <div className="state-card state-error" role="alert">
      <AlertCircle size={25} />
      <strong>{apiError?.status === 401 ? "인증이 필요합니다" : "요청을 완료하지 못했습니다"}</strong>
      <span>{apiError?.message ?? "네트워크 연결을 확인하고 다시 시도해 주세요."}</span>
      {apiError?.correlationId ? <code>문의 코드 {apiError.correlationId}</code> : null}
      {retry ? (
        <button className="button button-secondary" type="button" onClick={retry}>
          <RefreshCw size={16} /> 다시 시도
        </button>
      ) : null}
    </div>
  );
}

export function StatusBadge({ state }: { state: string }) {
  const normalized = state.toLowerCase().replaceAll("_", "-");
  return <span className={`status-badge status-${normalized}`}><span />{stateLabel(state)}</span>;
}

export function SuccessMark() {
  return <span className="success-mark"><CheckCircle2 size={34} /></span>;
}

function stateLabel(state: string) {
  return ({
    PENDING_PAYMENT: "결제 대기",
    READY: "준비 완료",
    APPROVING: "승인 중",
    CONFIRMING: "승인 중",
    APPROVED: "결제 완료",
    PAID: "결제 완료",
    ACCEPTED: "주문 접수",
    PREPARING: "제조 중",
    COMPLETED: "픽업 완료",
    CANCELLED: "취소됨",
    REJECTED: "거절됨",
    EXPIRED: "만료됨",
    UNKNOWN: "확인 중",
    RECONCILING: "복구 중",
    MANUAL_REVIEW: "확인 필요",
    FAILED: "실패",
  } as Record<string, string>)[state] ?? state;
}
