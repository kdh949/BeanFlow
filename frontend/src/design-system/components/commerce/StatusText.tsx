import type { ReactNode } from "react";

export type StatusTextTone = "neutral" | "uncertain" | "danger";
export type StatusTextProps = { tone?: StatusTextTone; children: ReactNode };

/** Text-first visual primitive. Product state meaning belongs to the presentation layer. */
export function StatusText({ tone = "neutral", children }: StatusTextProps) {
  return <span className={`bf-status-text bf-status-text--${tone}`}>{children}</span>;
}
