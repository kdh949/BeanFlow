import type { ReactNode } from "react";

export type PageHeadingProps = { title: string; action?: ReactNode };

/** Shared page heading without the retired eyebrow label. */
export function PageHeading({ title, action }: PageHeadingProps) {
  return (
    <header className="bf-page-heading">
      <div><h1>{title}</h1></div>
      {action}
    </header>
  );
}
