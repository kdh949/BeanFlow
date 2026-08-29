import {
  BadgeCheck,
  BadgeDollarSign,
  Bell,
  BookOpenText,
  BriefcaseBusiness,
  ChevronDown,
  ClipboardCheck,
  ClipboardList,
  ContactRound,
  FileText,
  Headset,
  Megaphone,
  MessageSquarePlus,
  Settings2,
  ShieldCheck,
  UserRound,
  UserRoundSearch,
} from "lucide-react";
import { useId, useState, type ReactNode } from "react";
import { NavLink, Outlet } from "react-router";
import { BrandLockup, Button, WorkspaceFrame } from "../../design-system";
import "./support-workspace.css";

type SupportDestination = {
  label: string;
  to?: string;
  icon: typeof Headset;
};

const supportPrimaryNavigation: SupportDestination[] = [
  { label: "대기열", to: "/support/queue", icon: ClipboardList },
  { label: "문의 접수", to: "/support/cases/new", icon: MessageSquarePlus },
  { label: "상담 상세", icon: ClipboardCheck },
  { label: "본인확인", icon: BadgeCheck },
  { label: "주문 문제 처리", icon: BriefcaseBusiness },
  { label: "고객 보상", to: "/support/compensations", icon: BadgeDollarSign },
  { label: "계정·정보 변경", to: "/support/profile-changes", icon: ContactRound },
  { label: "승인·감사함", to: "/support/approvals", icon: ShieldCheck },
];

const supportToolNavigation: SupportDestination[] = [
  { label: "고객 조회", icon: UserRoundSearch },
  { label: "정책 가이드", icon: BookOpenText },
  { label: "문구 템플릿", icon: FileText },
  { label: "공지사항", icon: Megaphone },
];

const supportSettingsNavigation: SupportDestination[] = [
  { label: "설정", icon: Settings2 },
];

export type SupportWorkspaceActor = {
  displayName: string;
  teamLabel: string;
};

export type SupportSidebarProps = {
  actor: SupportWorkspaceActor;
  logoutFailed?: boolean;
  onLogout?: () => void;
};

/** The only sidebar used by BeanFlow customer-support workspaces. */
export function SupportSidebar({ actor, logoutFailed = false, onLogout }: SupportSidebarProps) {
  const [accountOpen, setAccountOpen] = useState(false);
  const accountPanelId = useId();
  return (
    <aside className="bf-support-sidebar" aria-label="고객지원 사이드바">
      <BrandLockup to="/support/queue" />
      <nav aria-label="고객지원 메뉴">
        <SupportNavigationList destinations={supportPrimaryNavigation} />
        <section className="bf-support-nav-section" aria-labelledby="support-tools-label">
          <h2 id="support-tools-label">지원 도구</h2>
          <SupportNavigationList destinations={supportToolNavigation} />
        </section>
        <section className="bf-support-nav-section is-settings" aria-label="고객지원 설정">
          <SupportNavigationList destinations={supportSettingsNavigation} />
        </section>
      </nav>
      <div className="bf-support-account">
        <Button
          variant="ghost"
          size="sm"
          block
          onClick={() => setAccountOpen((current) => !current)}
          aria-expanded={accountOpen}
          aria-controls={accountPanelId}
        >
          <span className="bf-support-avatar" aria-hidden="true"><UserRound size={22} /></span>
          <span className="bf-support-actor-copy"><strong>{actor.displayName}</strong><small>{actor.teamLabel}</small></span>
          <ChevronDown size={16} aria-hidden="true" />
        </Button>
        {accountOpen ? (
          <div className="bf-support-account-menu" id={accountPanelId}>
            {onLogout ? <Button variant="ghost" size="sm" block onClick={onLogout}>로그아웃</Button> : <span>계정 메뉴는 연결되지 않았습니다.</span>}
            {logoutFailed ? <p role="alert">로그아웃에 실패했습니다. 다시 시도해 주세요.</p> : null}
          </div>
        ) : null}
      </div>
    </aside>
  );
}

function SupportNavigationList({ destinations }: { destinations: SupportDestination[] }) {
  return (
    <ul>
      {destinations.map((destination) => <li key={destination.label}><SupportNavigationItem destination={destination} /></li>)}
    </ul>
  );
}

function SupportNavigationItem({ destination }: { destination: SupportDestination }) {
  const Icon = destination.icon;
  const content = <><Icon size={19} aria-hidden="true" /><span>{destination.label}</span></>;
  if (!destination.to) {
    return (
      <span className="bf-support-nav-item is-unavailable" aria-disabled="true" title={`${destination.label} 메뉴는 아직 사용할 수 없습니다`}>
        {content}
        <span className="bf-support-a11y-only">사용할 수 없음</span>
      </span>
    );
  }
  return <NavLink className="bf-support-nav-item" to={destination.to} end>{content}</NavLink>;
}

export type SupportTopbarProps = {
  contextLabel?: string;
  hasUnreadNotification?: boolean;
};

/** Customer-support topbar built on the shared workspace height foundation. */
export function SupportTopbar({ contextLabel = "고객지원", hasUnreadNotification = false }: SupportTopbarProps) {
  return (
    <header className="bf-support-topbar">
      <div className="bf-support-topbar-actions">
        <span className="bf-support-notification" role="status" aria-label={hasUnreadNotification ? "읽지 않은 고객지원 알림 있음" : "새 고객지원 알림 없음"}>
          <Bell size={20} aria-hidden="true" />
          {hasUnreadNotification ? <span aria-hidden="true" /> : null}
        </span>
        <span className="bf-support-context"><Headset size={18} aria-hidden="true" /><strong>{contextLabel}</strong><ChevronDown size={15} aria-hidden="true" /></span>
      </div>
    </header>
  );
}

export type SupportWorkspaceShellProps = SupportSidebarProps & SupportTopbarProps & {
  children?: ReactNode;
};

/** Canonical customer-support frame. Support pages provide content only. */
export function SupportWorkspaceShell({ children, actor, logoutFailed, onLogout, ...topbarProps }: SupportWorkspaceShellProps) {
  return (
    <WorkspaceFrame
      sidebar={<SupportSidebar actor={actor} logoutFailed={logoutFailed} onLogout={onLogout} />}
      topbar={<SupportTopbar {...topbarProps} />}
      sidebarSize="wide"
      responsiveCollapse={false}
      contentId="support-workspace-navigation"
    >
      {children ?? <Outlet />}
    </WorkspaceFrame>
  );
}
