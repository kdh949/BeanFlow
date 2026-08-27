import { CheckCircle2, RefreshCw } from "lucide-react";
import type { ReactNode } from "react";
import { ApiRequestError } from "../../../api/client";
import { Button } from "../../components/core/Button";
import { FeedbackState } from "../../components/feedback/FeedbackState";

export function LoadingState({ label = "불러오는 중" }: { label?: string }) {
  return <FeedbackState kind="loading" title={label} description="잠시만 기다려 주세요." />;
}

export function EmptyState({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return <FeedbackState kind="empty" title={title} description={description} action={action} />;
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const apiError = error instanceof ApiRequestError ? error : null;
  return (
    <FeedbackState
      kind="error"
      title={apiError?.status === 401 ? "인증이 필요합니다" : "요청을 완료하지 못했습니다"}
      description={apiError?.message ?? (error instanceof Error ? error.message : "네트워크 연결을 확인하고 다시 시도해 주세요.")}
      reference={apiError?.correlationId}
      action={retry ? <Button variant="secondary" onClick={retry}><RefreshCw size={16} /> 다시 시도</Button> : undefined}
    />
  );
}

export function SuccessMark() {
  return <span className="success-mark"><CheckCircle2 size={34} /></span>;
}
