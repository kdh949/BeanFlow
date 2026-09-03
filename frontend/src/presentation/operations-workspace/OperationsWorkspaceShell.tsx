import { Bell, ChevronDown, ClipboardCheck, FileText, LayoutDashboard, Settings2, ShieldCheck, UserRound } from "lucide-react";
import { useId, useState, type ReactNode } from "react";
import { NavLink, Outlet } from "react-router";
import { BrandLockup, Button, WorkspaceFrame } from "../../design-system";
import "./operations-workspace.css";

type OperationsDestination = {
  label: string;
  to?: string;
  end?: boolean;
  icon: typeof LayoutDashboard;
};

const operationsNavigation: OperationsDestination[] = [
  { label: "운영 대시보드", to: "/ops", end: true, icon: LayoutDashboard },
  { label: "보상 조회", to: "/ops/orders", icon: ClipboardCheck },
  { label: "점주 계정 관리", to: "/ops/merchant-accounts", icon: UserRound },
  { label: "정책 관리", to: "/ops/policies", icon: ShieldCheck },
  { label: "감사 로그", icon: FileText },
  { label: "설정", icon: Settings2 },
];

export type OperationsWorkspaceActor = {
  displayName: string;
  roleLabel: string;
};

export function OperationsSidebar() {
  return (
    <aside className="bf-operations-sidebar" aria-label="운영 사이드바">
      <BrandLockup to="/ops" />
      <nav aria-label="운영 메뉴">
        <ul>
          {operationsNavigation.map(({ label, to, end, icon: Icon }) => (
            <li key={label}>
              {to ? (
                <NavLink className="bf-operations-nav-item" to={to} end={end}>
                  <Icon size={18} aria-hidden="true" /><span>{label}</span>
                </NavLink>
              ) : (
                <span className="bf-operations-nav-item is-unavailable" aria-disabled="true" title={`${label}은 아직 사용할 수 없습니다`}>
                  <Icon size={18} aria-hidden="true" /><span>{label}</span><span className="bf-operations-a11y-only">사용할 수 없음</span>
                </span>
              )}
            </li>
          ))}
        </ul>
      </nav>
    </aside>
  );
}

export type OperationsTopbarProps = {
  actor: OperationsWorkspaceActor;
  hasUnreadNotification?: boolean;
  logoutFailed?: boolean;
  onLogout?: () => void;
};

export function OperationsTopbar({ actor, hasUnreadNotification = false, logoutFailed = false, onLogout }: OperationsTopbarProps) {
  const [accountOpen, setAccountOpen] = useState(false);
  const accountPanelId = useId();
  return (
    <header className="bf-operations-topbar">
      <span>플랫폼 운영</span>
      <div>
        <span className="bf-operations-notification" role="status" aria-label={hasUnreadNotification ? "읽지 않은 알림 있음" : "새 알림 없음"}>
          <Bell size={19} aria-hidden="true" />{hasUnreadNotification ? <span aria-hidden="true" /> : null}
        </span>
        <div className="bf-operations-account">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setAccountOpen((current) => !current)}
            aria-expanded={accountOpen}
            aria-controls={accountPanelId}
            aria-label={`운영 계정 ${actor.displayName}, ${actor.roleLabel}`}
          >
            <span className="bf-operations-avatar" aria-hidden="true"><UserRound size={19} /></span>
            <span className="bf-operations-actor-copy"><strong>{actor.displayName}</strong><small>{actor.roleLabel}</small></span>
            <ChevronDown size={15} aria-hidden="true" />
          </Button>
          {accountOpen ? (
            <div className="bf-operations-account-menu" id={accountPanelId}>
              {onLogout ? <Button variant="ghost" size="sm" block onClick={onLogout}>로그아웃</Button> : <span>계정 메뉴는 연결되지 않았습니다.</span>}
              {logoutFailed ? <p role="alert">로그아웃에 실패했습니다. 다시 시도해 주세요.</p> : null}
            </div>
          ) : null}
        </div>
      </div>
    </header>
  );
}

export type OperationsWorkspaceShellProps = OperationsTopbarProps & { children?: ReactNode };

export function OperationsWorkspaceShell({ actor, hasUnreadNotification, logoutFailed, onLogout, children }: OperationsWorkspaceShellProps) {
  return (
    <WorkspaceFrame
      sidebar={<OperationsSidebar />}
      topbar={<OperationsTopbar actor={actor} hasUnreadNotification={hasUnreadNotification} logoutFailed={logoutFailed} onLogout={onLogout} />}
      sidebarSize="wide"
      contentId="operations-workspace-navigation"
    >
      {children ?? <Outlet />}
    </WorkspaceFrame>
  );
}
