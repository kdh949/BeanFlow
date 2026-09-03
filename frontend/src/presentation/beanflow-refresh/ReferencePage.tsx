import type { ReactNode } from "react";

export type CustomerReferencePageProps = {
  /** Visible route title. Omit only when the first child owns the result heading. */
  title?: string;
  /** Optional concise guidance shown directly below the title. */
  description?: string;
  /** Selects only documented page geometry; feature styling stays in semantic child classes. */
  layout?: "standard" | "auth" | "result" | "list";
  children: ReactNode;
};

/** Canonical wrapper for the image-led customer routes. It deliberately owns no remote state. */
export function CustomerReferencePage({
  title,
  description,
  layout = "standard",
  children,
}: CustomerReferencePageProps) {
  return (
    <div className={`bfr-reference-page bfr-reference-page--${layout}`}>
      {title ? (
        <header className="bfr-reference-heading">
          <h1>{title}</h1>
          {description ? <p>{description}</p> : null}
        </header>
      ) : null}
      {children}
    </div>
  );
}

export type ReferenceSectionProps = {
  title?: string;
  children: ReactNode;
  tone?: "default" | "soft" | "danger";
};

/** Reusable bordered surface used by customer summaries, forms, and lists. */
export function ReferenceSection({ title, children, tone = "default" }: ReferenceSectionProps) {
  return (
    <section className={`bfr-reference-section bfr-reference-section--${tone}`}>
      {title ? <h2>{title}</h2> : null}
      {children}
    </section>
  );
}

export type WorkspaceReferencePageProps = {
  title: string;
  description?: string;
  action?: ReactNode;
  density?: "comfortable" | "dense";
  children: ReactNode;
};

/** Shared content-slot frame for store and operations pages; it never renders workspace chrome. */
export function WorkspaceReferencePage({ title, description, action, density = "comfortable", children }: WorkspaceReferencePageProps) {
  return (
    <div className={`bfr-workspace-page bfr-workspace-page--${density}`}>
      <header className="bfr-workspace-heading">
        <div><h1>{title}</h1>{description ? <p>{description}</p> : null}</div>
        {action ? <div>{action}</div> : null}
      </header>
      {children}
    </div>
  );
}
