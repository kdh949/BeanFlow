import type { ReactNode } from "react";

export type PageHeadingProps = { title: string; description?: string; action?: ReactNode };

/** Shared page heading without the retired eyebrow label. */
export function PageHeading({ title, description, action }: PageHeadingProps) {
  return (
    <header className="bf-page-heading">
      <div><h1>{title}</h1>{description ? <p>{description}</p> : null}</div>
      {action}
    </header>
  );
}
