import {
  Archive,
  Bell,
  ChartNoAxesColumnIncreasing,
  ChevronDown,
  ChevronLeft,
  CircleAlert,
  CircleDollarSign,
  CircleHelp,
  Clock3,
  MapPin,
  MessagesSquare,
  ReceiptText,
  Store,
  UserRound,
  UsersRound,
} from "lucide-react";
import { useState, type ReactNode } from "react";
import { NavLink, Outlet } from "react-router";
import { BrandLockup, Button, WorkspaceFrame } from "../../design-system";
import "./merchant-workspace.css";

type MerchantDestination = {
  label: string;
  to?: string;
  ownerOnly?: boolean;
  icon: typeof Store;
};

type MerchantNavigationGroup = {
  label: string;
  destinations: MerchantDestination[];
};

const merchantNavigation: MerchantNavigationGroup[] = [
  {
    label: "주문 관리",
    destinations: [
      { label: "주문 보드", to: "/store", icon: ReceiptText },
      { label: "주문 내역", icon: ReceiptText },
    ],
  },
  {
    label: "정산 관리",
    destinations: [
      { label: "정산", to: "/store/settlements", ownerOnly: true, icon: Archive },
      { label: "부분 환불", icon: Archive },
      { label: "이의제기", to: "/store/disputes", ownerOnly: true, icon: CircleAlert },
      { label: "운영 지역", to: "/store/region", icon: MapPin },
    ],
  },
  {
    label: "매장 관리",
    destinations: [
      { label: "매장 정보", icon: Store },
      { label: "운영 시간", icon: Clock3 },
      { label: "직원 관리", icon: UsersRound },
    ],
  },
  {
    label: "고객 관리",
    destinations: [{ label: "고객 문의", icon: MessagesSquare }],
  },
  {
    label: "리포트",
    destinations: [
      { label: "매출 리포트", icon: ChartNoAxesColumnIncreasing },
      { label: "정산 관리", icon: CircleDollarSign },
    ],
  },
];

export type MerchantWorkspaceStore = {
  name: string;
  reference?: string;
};

export type MerchantWorkspaceActor = {
  displayName: string;
  roleLabel: string;
};

export type MerchantSidebarProps = {
  collapsed: boolean;
  canManageOwnerRoutes?: boolean;
  onToggle: () => void;
};

/** The only sidebar used by BeanFlow store workspaces. */
export function MerchantSidebar({ collapsed, canManageOwnerRoutes = true, onToggle }: MerchantSidebarProps) {
  return (
    <aside className={`bf-merchant-sidebar${collapsed ? " is-collapsed" : ""}`} aria-label="스토어 사이드바">
      <BrandLockup to="/store" compact={collapsed} />
      <nav aria-label="스토어 메뉴">
        {merchantNavigation.map((group) => (
          <section className="bf-merchant-nav-group" key={group.label} aria-labelledby={`merchant-nav-${group.label}`}>
            <h2 id={`merchant-nav-${group.label}`}>{group.label}</h2>
            <ul>
              {group.destinations.map((destination) => (
                <li key={`${group.label}-${destination.label}`}>
                  <MerchantNavigationItem
                    destination={destination}
                    collapsed={collapsed}
                    enabled={!destination.ownerOnly || canManageOwnerRoutes}
                  />
                </li>
              ))}
            </ul>
          </section>
        ))}
      </nav>
      <div className="bf-merchant-sidebar-toggle">
        <Button variant="ghost" size="sm" onClick={onToggle} aria-expanded={!collapsed} aria-label={collapsed ? "메뉴 펼치기" : "메뉴 접기"}>
          <span className="bf-merchant-toggle-icon"><ChevronLeft className={collapsed ? "is-reversed" : undefined} size={16} aria-hidden="true" /></span>
          <span className="bf-merchant-toggle-label">{collapsed ? "메뉴 펼치기" : "메뉴 접기"}</span>
        </Button>
      </div>
    </aside>
  );
}

function MerchantNavigationItem({ destination, collapsed, enabled }: { destination: MerchantDestination; collapsed: boolean; enabled: boolean }) {
  const Icon = destination.icon;
  const content = <><Icon size={17} aria-hidden="true" /><span>{destination.label}</span></>;
  if (!destination.to || !enabled) {
    return (
      <span className="bf-merchant-nav-item is-unavailable" aria-disabled="true" title={`${destination.label}은 아직 사용할 수 없습니다`}>
        {content}
        <span className="bf-a11y-only">사용할 수 없음</span>
      </span>
    );
  }
  return (
    <NavLink
      className="bf-merchant-nav-item"
      to={destination.to}
      end={destination.to === "/store"}
      aria-label={collapsed ? destination.label : undefined}
    >
      {content}
    </NavLink>
  );
}

export type MerchantTopbarProps = {
  store: MerchantWorkspaceStore;
  actor: MerchantWorkspaceActor;
  hasUnreadNotification?: boolean;
  logoutFailed?: boolean;
  onLogout?: () => void;
  onStoreMenuClick?: () => void;
};

/** The only topbar used by BeanFlow store workspaces. */
export function MerchantTopbar({
  store,
  actor,
  hasUnreadNotification = false,
  logoutFailed = false,
  onLogout,
  onStoreMenuClick,
}: MerchantTopbarProps) {
  const [accountOpen, setAccountOpen] = useState(false);
  const storeContent = <><Store size={18} aria-hidden="true" /><strong>{store.name}</strong>{store.reference ? <span>{store.reference}</span> : null}<ChevronDown size={15} aria-hidden="true" /></>;
  return (
    <header className="bf-merchant-topbar">
      <div className="bf-merchant-store-switch">
        {onStoreMenuClick
          ? <Button variant="secondary" size="sm" onClick={onStoreMenuClick} aria-label={`${store.name} 매장 선택`}>{storeContent}</Button>
          : <div className="bf-merchant-store-static" aria-label={`현재 매장 ${store.name}`}>{storeContent}</div>}
      </div>
      <div className="bf-merchant-topbar-actions">
        <span className="bf-merchant-notification" role="status" aria-label={hasUnreadNotification ? "읽지 않은 알림 있음" : "새 알림 없음"}>
          <Bell size={19} aria-hidden="true" />
          {hasUnreadNotification ? <span aria-hidden="true" /> : null}
        </span>
        <span className="bf-merchant-help"><CircleHelp size={18} aria-hidden="true" /><span>도움말</span></span>
        <div className="bf-merchant-account">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setAccountOpen((current) => !current)}
            aria-expanded={accountOpen}
            aria-controls="merchant-account-panel"
          >
            <span className="bf-merchant-avatar" aria-hidden="true"><UserRound size={20} /></span>
            <span className="bf-merchant-actor-copy"><strong>{actor.displayName}</strong><small>{actor.roleLabel}</small></span>
            <ChevronDown size={15} aria-hidden="true" />
          </Button>
          {accountOpen ? (
            <div className="bf-merchant-account-menu" id="merchant-account-panel">
              {onLogout ? <Button variant="ghost" size="sm" block onClick={onLogout}>로그아웃</Button> : <span>계정 메뉴는 연결되지 않았습니다.</span>}
              {logoutFailed ? <p role="alert">로그아웃에 실패했습니다. 다시 시도해 주세요.</p> : null}
            </div>
          ) : null}
        </div>
      </div>
    </header>
  );
}

export type MerchantWorkspaceShellProps = MerchantTopbarProps & {
  children?: ReactNode;
  canManageOwnerRoutes?: boolean;
  defaultCollapsed?: boolean;
};

/** Canonical store frame. Store pages provide content only. */
export function MerchantWorkspaceShell({
  children,
  canManageOwnerRoutes = true,
  defaultCollapsed = false,
  ...topbarProps
}: MerchantWorkspaceShellProps) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed);
  return (
    <WorkspaceFrame
      sidebar={<MerchantSidebar collapsed={collapsed} canManageOwnerRoutes={canManageOwnerRoutes} onToggle={() => setCollapsed((current) => !current)} />}
      topbar={<MerchantTopbar {...topbarProps} />}
      sidebarSize={collapsed ? "compact" : "standard"}
      contentId="merchant-workspace-navigation"
    >
      {children ?? <Outlet />}
    </WorkspaceFrame>
  );
}
