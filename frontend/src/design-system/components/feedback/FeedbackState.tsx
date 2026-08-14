import { AlertCircle, Clock3, LoaderCircle } from "lucide-react";
import type { ReactNode } from "react";

export type FeedbackStateProps = {
  /** State semantics used for iconography and assistive-technology announcements. */
  kind: "loading" | "empty" | "error";
  /** Short outcome or current activity. */
  title: string;
  /** Cause, context, or next useful action in plain language. */
  description: string;
  /** Optional canonical action such as retry or navigation. */
  action?: ReactNode;
  /** Optional support-safe correlation reference. Never pass credentials or personal data. */
  reference?: string;
};

/** Shared full-section feedback for loading, empty, and recoverable error states. */
export function FeedbackState({ kind, title, description, action, reference }: FeedbackStateProps) {
  const Icon = kind === "loading" ? LoaderCircle : kind === "error" ? AlertCircle : Clock3;

  return (
    <section
      className={`bf-feedback bf-feedback--${kind}`}
      role={kind === "error" ? "alert" : "status"}
      aria-live={kind === "error" ? "assertive" : "polite"}
    >
      <span className="bf-feedback__glyph" aria-hidden="true">
        <Icon className={kind === "loading" ? "bf-feedback__spinner" : undefined} size={25} />
      </span>
      <strong>{title}</strong>
      <span>{description}</span>
      {reference ? <code>문의 코드 {reference}</code> : null}
      {action}
    </section>
  );
}
