import { CheckCircle2 } from "lucide-react";
import type { ReactNode } from "react";
import { FeedbackState } from "../../components/feedback/FeedbackState";

export function LoadingState({ label = "불러오는 중" }: { label?: string }) {
  return <FeedbackState kind="loading" title={label} description="잠시만 기다려 주세요." />;
}

export function EmptyState({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return <FeedbackState kind="empty" title={title} description={description} action={action} />;
}

export function SuccessMark() {
  return <span className="success-mark"><CheckCircle2 size={34} /></span>;
}
