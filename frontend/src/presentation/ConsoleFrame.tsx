import { useId, useState, type ReactNode } from "react";
import { Link, NavLink } from "react-router";
import { BarChart3, CircleDotDashed, Headset, LifeBuoy, LogOut, MapPin, Menu, PackageCheck, ReceiptText, Search, Settings2, Store, TicketPercent, UserRound, WalletCards } from "lucide-react";
import { BrandLockup, Button, InlineNotice } from "../design-system";
import "./beanflow-refresh/refresh.css";

export type ConsoleKind = "store" | "ops" | "support";
export type ConsoleAccess = "authenticated" | "initial-password" | "unauthenticated" | "checking" | "unavailable";
export type ConsoleFrameProps = {
  kind: ConsoleKind;
  /** Display state only. Route gates and the server remain the authorization boundary. */
  access: ConsoleAccess;
  actorLabel: string;
  ownsAnyStore?: boolean;
  membershipState?: "checking" | "ready" | "failed";
  onRetryMembership?: () => void;
  onLogOut: () => Promise<void>;
  children: ReactNode;
};

/** The single responsive console frame. Session adapters supply state; this component owns navigation and account actions. */
export function ConsoleFrame({ kind, access, actorLabel, ownsAnyStore = false, membershipState, onRetryMembership, onLogOut, children }: ConsoleFrameProps) {
  const navigationId = useId();
  const [menuOpen, setMenuOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [logoutFailed, setLogoutFailed] = useState(false);
  const workspace = access === "authenticated";
  const signedIn = workspace || access === "initial-password";
  const basePath = kind === "store" ? "/store" : kind === "ops" ? "/ops" : "/support";
  const context = kind === "store" ? "매장 운영" : kind === "ops" ? "플랫폼 운영" : "고객지원";
  const storeItems = [
    { to: "/store", label: "주문 관리", icon: PackageCheck, end: true },
    ...(ownsAnyStore ? [
      { to: "/store/settlements", label: "정산 내역", icon: WalletCards, end: false },
      { to: "/store/disputes", label: "이의제기", icon: ReceiptText, end: false },
    ] : []),
    { to: "/store/management", label: "매장 관리", icon: Settings2, end: false },
    { to: "/store/region", label: "매장 설정", icon: MapPin, end: false },
  ];
  const opsItems = [
    { to: "/ops", label: "운영 홈", icon: BarChart3, end: true },
    { to: "/ops/orders", label: "주문 보상 조회", icon: Search, end: false },
    { to: "/ops/merchant-accounts", label: "점주 계정", icon: UserRound, end: false },
    { to: "/ops/stores", label: "매장 관리", icon: Store, end: false },
    { to: "/ops/recovery", label: "문제 확인 및 복구", icon: LifeBuoy, end: false },
    { to: "/ops/support-investigations", label: "상담 요청 검토", icon: Search, end: false },
    { to: "/ops/control", label: "운영 업무", icon: CircleDotDashed, end: false },
    { to: "/ops/policies", label: "정책 관리", icon: Settings2, end: false },
    { to: "/ops/campaigns", label: "쿠폰 캠페인", icon: TicketPercent, end: false },
  ];
  const supportItems = [
    { to: "/support", label: "고객지원", icon: Headset, end: true },
    { to: "/support/cases", label: "상담 목록", icon: ReceiptText, end: false },
    { to: "/support/inquiries", label: "고객 문의", icon: ReceiptText, end: false },
  ];
  const unavailablePaths = new Set<string>();
  const items = kind === "store" ? storeItems : kind === "ops" ? opsItems : supportItems;

  async function logOut() {
    if (loggingOut) return;
    setLoggingOut(true);
    setLogoutFailed(false);
    try { await onLogOut(); }
    catch { setLogoutFailed(true); }
    finally { setLoggingOut(false); }
  }

  return <div className={`bfr-store-shell${workspace ? "" : " is-auth"}`}>
    {workspace ? <aside className="bfr-store-sidebar">
      <div className="bfr-store-brand-row"><BrandLockup to={basePath} /><div className="bfr-console-menu-toggle"><Button variant="secondary" aria-expanded={menuOpen} aria-controls={navigationId} onClick={() => setMenuOpen(!menuOpen)}><Menu size={18} aria-hidden="true" />업무 메뉴</Button></div></div>
      <div className="bfr-console-navigation" id={navigationId} data-open={menuOpen}>
        <nav aria-label={`${context} 메뉴`}>{items.map(({ to, label, icon: Icon, end }) => unavailablePaths.has(to) ? <span key={to} className="bfr-console-unavailable" role="link" aria-disabled="true"><Icon size={18} aria-hidden="true" /><span>{label}<small>준비 중</small></span></span> : <NavLink key={to} to={to} end={end} onClick={() => setMenuOpen(false)}><Icon size={18} aria-hidden="true" /><span>{label}</span></NavLink>)}</nav>
        {membershipState === "checking" ? <p className="bfr-membership-state" role="status">매장 권한 확인 중</p> : null}
        {membershipState === "failed" ? <InlineNotice tone="warning" announce="polite" title="매장 권한을 확인하지 못했습니다" description="정산·이의제기 메뉴를 확인하려면 다시 시도해 주세요." action={<Button variant="secondary" size="sm" onClick={onRetryMembership}>매장 권한 다시 확인</Button>} /> : null}
        <div className="bfr-store-sidebar-foot"><Link to="/app"><Store size={17} aria-hidden="true" />고객 앱</Link></div>
      </div>
    </aside> : null}
    <section className="bfr-store-main">
      <header className="bfr-store-topbar">
        {workspace ? <span className="bfr-store-context">{context}</span> : <BrandLockup to="/" />}
        <div className="bfr-store-account-actions">
          <div className="bfr-store-actor" aria-label={`${context} 계정 상태`}><UserRound size={18} aria-hidden="true" /><span>{actorLabel}</span></div>
          {signedIn ? <Button variant="ghost" loading={loggingOut} onClick={() => void logOut()}><LogOut size={18} aria-hidden="true" />로그아웃</Button> : null}
        </div>
      </header>
      <main className="bfr-store-content">
        {logoutFailed ? <InlineNotice tone="danger" announce="assertive" title="로그아웃하지 못했습니다" description="세션 종료를 확인하지 못했습니다. 다시 시도해 주세요." action={<Button variant="secondary" loading={loggingOut} onClick={() => void logOut()}>로그아웃 다시 시도</Button>} /> : null}
        {children}
      </main>
    </section>
  </div>;
}
