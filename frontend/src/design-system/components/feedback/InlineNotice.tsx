import type { ReactNode } from "react";

export type InlineNoticeProps = {
  tone?: "info" | "warning" | "danger";
  title: string;
  description: string;
  action?: ReactNode;
  announce?: "off" | "polite" | "assertive";
};

/** Presentation-safe inline guidance. Product error objects must be mapped before reaching this component. */
export function InlineNotice({ tone = "info", title, description, action, announce = "off" }: InlineNoticeProps) {
  const role = announce === "assertive" ? "alert" : announce === "polite" ? "status" : undefined;
  return (
    <div className={`bf-inline-notice bf-inline-notice--${tone}`} role={role} aria-live={announce === "off" ? undefined : announce}>
      <div><strong>{title}</strong><p>{description}</p></div>
      {action ? <div className="bf-inline-notice__action">{action}</div> : null}
    </div>
  );
}
