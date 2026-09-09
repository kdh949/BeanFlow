import { AlertCircle, Clock3, LoaderCircle } from "lucide-react";
import type { ReactNode } from "react";

export type FeedbackStateProps = {
  kind: "loading" | "empty" | "error";
  title: string;
  description: string;
  action?: ReactNode;
  reference?: string;
};

/** Shared explicit loading, empty, and failure state. Dependency failures are never rendered as empty. */
export function FeedbackState({ kind, title, description, action, reference }: FeedbackStateProps) {
  const Icon = kind === "loading" ? LoaderCircle : kind === "error" ? AlertCircle : Clock3;
  return (
    <section
      className={`bf-feedback bf-feedback--${kind}`}
      role={kind === "error" ? "alert" : "status"}
      aria-live={kind === "error" ? "assertive" : "polite"}
    >
      <Icon className={kind === "loading" ? "bf-spin" : undefined} size={24} aria-hidden="true" />
      <strong>{title}</strong>
      <p>{description}</p>
      {reference ? <code>문의 코드 {reference}</code> : null}
      {action}
    </section>
  );
}
