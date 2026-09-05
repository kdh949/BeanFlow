import {
  BarChart3, Bell, ChevronDown, CircleDotDashed, ClipboardCheck, Headset, Home, LifeBuoy, LogOut, MapPin, PackageCheck, ReceiptText,
  Search, Settings2, ShieldCheck, ShoppingBag, Store, UserRound, WalletCards,
} from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { Link, NavLink, Outlet } from "react-router";
import { ApiRequestError, unwrap } from "../api/client";
import { customerApi } from "../api/customerClient";
import { operationsAuth, useOperationsAuth } from "../auth/session";
import { BrandLockup, Button, ButtonLink } from "../design-system";
import { merchantSession, requestMerchantStores, useMerchantSession } from "../features/auth/merchant/merchantSession";
import { CUSTOMER_NOTIFICATION_SUMMARY_CHANGED } from "../features/notification/notificationSummary";
import "./beanflow-refresh/refresh.css";

type BellState = "loading" | "read" | "unread" | "failed" | "unauthenticated";

export function NotificationAction() {
  const [state, setState] = useState<BellState>("loading");
  const load = useCallback(async () => {
    setState((current) => current === "read" || current === "unread" ? current : "loading");
    try {
      const result = unwrap(await customerApi.GET("/me/notification-summary"));
      setState(result.hasUnread ? "unread" : "read");
    } catch (failure) {
      setState(failure instanceof ApiRequestError && failure.status === 401 ? "unauthenticated" : "failed");
    }
  }, []);

  useEffect(() => {
    void load();
    const reload = () => void load();
    window.addEventListener(CUSTOMER_NOTIFICATION_SUMMARY_CHANGED, reload);
    return () => window.removeEventListener(CUSTOMER_NOTIFICATION_SUMMARY_CHANGED, reload);
  }, [load]);

  const label = state === "failed"
    ? "알림 상태를 확인하지 못했습니다. 알림함 열기"
    : state === "unread" ? "읽지 않은 알림 있음. 알림함 열기" : "알림함 열기";
  return (
    <Link className={`bfr-header-action ${state === "failed" ? "is-failed" : ""}`} to="/app/notifications" aria-label={label} aria-busy={state === "loading" || undefined}>
      <Bell size={19} aria-hidden="true" />
      {state === "unread" || state === "failed" ? <span aria-hidden="true">{state === "failed" ? "!" : ""}</span> : null}
    </Link>
  );
}

/** Single customer frame used by every customer route. */
export function CustomerShell() {
  return (
    <div className="bfr-customer-stage">
      <div className="bfr-customer-app">
        <header className="bfr-customer-header">
          <BrandLockup to="/app" />
          <div className="bfr-header-actions">
            <Link className="bfr-header-action" to="/app/cart" aria-label="장바구니 열기"><ShoppingBag size={19} aria-hidden="true" /></Link>
            <NotificationAction />
          </div>
        </header>
        <main className="bfr-customer-content"><Outlet /></main>
        <nav className="bfr-customer-tabs" aria-label="고객 메뉴">
          <NavLink to="/app" end><Home size={20} /><span>홈</span></NavLink>
          <NavLink to="/app/stores"><Search size={20} /><span>매장</span></NavLink>
          <NavLink to="/app/orders"><ReceiptText size={20} /><span>주문</span></NavLink>
          <NavLink to="/app/me"><UserRound size={20} /><span>마이</span></NavLink>
        </nav>
      </div>
    </div>
  );
}

type ConsoleKind = "store" | "ops" | "support";

/** Shared dense workspace frame for store, operations, and support routes. */
export function ConsoleShell({ kind }: { kind: ConsoleKind }) {
  const ownsAnyStore = useOwnerMembership(kind === "store");
  const merchant = useMerchantSession();
  const operations = useOperationsAuth();
  const [logoutFailed, setLogoutFailed] = useState(false);
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
    { to: "/ops", label: "운영 현황", icon: BarChart3, end: true },
    { to: "/ops/orders", label: "주문 조회", icon: Search, end: false },
    { to: "/ops/merchant-accounts", label: "점주 계정", icon: UserRound, end: false },
    { to: "/ops/recovery", label: "문제 확인 및 복구", icon: LifeBuoy, end: false },
    { to: "/ops/control", label: "운영 업무", icon: CircleDotDashed, end: false },
    { to: "/ops/policies", label: "정책 관리", icon: Settings2, end: false },
  ];
  const supportItems = [
    { to: "/support", label: "고객지원", icon: Headset, end: true },
    { to: "/support/follow-up", label: "상담 후속 업무", icon: ClipboardCheck, end: false },
  ];
  const items = kind === "store" ? storeItems : kind === "ops" ? opsItems : supportItems;
  const basePath = kind === "store" ? "/store" : kind === "ops" ? "/ops" : "/support";
  const context = kind === "store" ? "매장 운영" : kind === "ops" ? "플랫폼 운영" : "고객지원";
  const actor = kind === "store"
    ? merchant.status === "authenticated" || merchant.status === "initialPassword" ? merchant.actor.displayName : "인증 필요"
    : operations.status === "authenticated" ? "OIDC 인증됨" : operations.status === "unavailable" ? "인증 설정 오류" : "로그인 필요";

  async function logOut() {
    setLogoutFailed(false);
    try {
      if (kind === "store") await merchantSession.logOut();
      else await operationsAuth.logOut();
    } catch {
      setLogoutFailed(true);
    }
  }

  return (
    <div className="bfr-store-shell">
      <aside className="bfr-store-sidebar">
        <BrandLockup to={basePath} />
        <span className="bfr-store-context">{context}</span>
        <nav aria-label={`${context} 메뉴`}>
          {items.map(({ to, label, icon: Icon, end }) => <NavLink key={to} to={to} end={end}><Icon size={18} /><span>{label}</span></NavLink>)}
        </nav>
        <div className="bfr-store-sidebar-foot">
          <Link to="/app"><Store size={17} />고객 앱</Link>
          <Button variant="ghost" size="sm" onClick={() => void logOut()}><LogOut size={17} />로그아웃</Button>
          {logoutFailed ? <p role="alert">로그아웃에 실패했습니다. 다시 시도해 주세요.</p> : null}
        </div>
      </aside>
      <section className="bfr-store-main">
        <header className="bfr-store-topbar">
          <div><span>{context}</span></div>
          <div className="bfr-store-actor" aria-label={`${context} 계정 상태`}><span>{actor.slice(0, 1)}</span>{actor}<ChevronDown size={15} aria-hidden="true" /></div>
        </header>
        <main className="bfr-store-content"><Outlet /></main>
      </section>
    </div>
  );
}

function useOwnerMembership(enabled: boolean): boolean {
  const [ownsAnyStore, setOwnsAnyStore] = useState(false);
  const session = useMerchantSession();
  useEffect(() => {
    if (!enabled || session.status !== "authenticated") { setOwnsAnyStore(false); return; }
    let disposed = false;
    void requestMerchantStores()
      .then((stores) => { if (!disposed) setOwnsAnyStore(stores.some((store) => store.membershipRole === "OWNER")); })
      .catch(() => { if (!disposed) setOwnsAnyStore(false); });
    return () => { disposed = true; };
  }, [enabled, session.status]);
  return ownsAnyStore;
}

export function RootRedirect() {
  return (
    <main className="surface-card root-choice">
      <BrandLockup />
      <h1>어떤 화면을 열까요?</h1>
      <div>
        <ButtonLink to="/app">고객 앱</ButtonLink>
        <ButtonLink variant="secondary" to="/store"><Store size={18} /> 매장 콘솔</ButtonLink>
        <ButtonLink variant="secondary" to="/ops"><ShieldCheck size={18} /> 운영 콘솔</ButtonLink>
        <ButtonLink variant="secondary" to="/support"><Headset size={18} /> 고객지원 콘솔</ButtonLink>
      </div>
    </main>
  );
}
