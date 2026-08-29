import type { ReactNode } from "react";

export type WorkspaceSidebarSize = "compact" | "standard" | "wide";

export type WorkspaceFrameProps = {
  /** Sidebar landmark supplied by the surface-specific chrome owner. */
  sidebar: ReactNode;
  /** Topbar landmark supplied by the surface-specific chrome owner. */
  topbar: ReactNode;
  /** Route or Storybook content rendered in the workspace main landmark. */
  children: ReactNode;
  /** Semantic width preset. Store uses standard, Support uses wide, and collapsed navigation uses compact. */
  sidebarSize?: WorkspaceSidebarSize;
  /** Collapse standard or wide sidebars to the compact foundation width below the workspace breakpoint. */
  responsiveCollapse?: boolean;
  /** Stable anchor for skip links or shell-level navigation targets. */
  contentId?: string;
};

/**
 * Application-neutral workspace geometry. It owns only sidebar width, topbar height,
 * and the content slot; route, menu, actor, and session semantics stay with each surface.
 */
export function WorkspaceFrame({
  sidebar,
  topbar,
  children,
  sidebarSize = "standard",
  responsiveCollapse = true,
  contentId,
}: WorkspaceFrameProps) {
  return (
    <div className={`bf-workspace-frame is-sidebar-${sidebarSize}${responsiveCollapse ? " is-responsive-compact" : ""}`}>
      {sidebar}
      <section className="bf-workspace-frame__main">
        {topbar}
        <main className="bf-workspace-frame__content" id={contentId}>{children}</main>
      </section>
    </div>
  );
}
