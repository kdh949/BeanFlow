import { Bell, ChevronDown, Headset, Home, LogOut, ReceiptText, Search, ShieldCheck, ShoppingBag, Store, UserRound } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router";
import { ApiRequestError, unwrap } from "../api/client";
import { customerApi } from "../api/customerClient";
import { operationsAuth, useOperationsAuth } from "../auth/session";
import { BrandLockup, Button, ButtonLink } from "../design-system";
import { merchantSession, requestMerchantStores, useMerchantSession } from "../features/auth/merchant/merchantSession";
import type { MerchantStore } from "../features/auth/merchant/merchantSession";
import { CUSTOMER_NOTIFICATION_SUMMARY_CHANGED } from "../features/notification/notificationSummary";
import "./beanflow-refresh/refresh.css";
import { MerchantWorkspaceShell } from "./merchant-workspace";
import { SupportWorkspaceShell } from "./support-workspace";
import { OperationsWorkspaceShell } from "./operations-workspace";

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
  const location = useLocation();
  const authOnly = location.pathname === "/app/login" || location.pathname === "/app/signup";
  const referenceTarget = [
    "/app/help",
    "/app/orders",
    "/app/points",
    "/app/coupons",
    "/app/favorites",
  ].some((path) => location.pathname === path) || location.pathname.includes("/payments/");
  return (
    <div className={`bfr-customer-stage ${authOnly ? "is-auth-route" : ""}`}>
      <div className="bfr-customer-app">
        <header className="bfr-customer-header">
          <BrandLockup to="/app" />
          {!authOnly ? (
            <div className="bfr-header-actions">
              {!referenceTarget ? <Link className="bfr-header-action" to="/app/cart" aria-label="장바구니 열기"><ShoppingBag size={19} aria-hidden="true" /></Link> : null}
              <NotificationAction />
            </div>
          ) : null}
        </header>
        <main className="bfr-customer-content"><Outlet /></main>
        {!authOnly ? (
          <nav className="bfr-customer-tabs" aria-label="고객 메뉴">
            <NavLink to="/app" end><Home size={20} /><span>홈</span></NavLink>
            <NavLink to="/app/stores"><Search size={20} /><span>매장</span></NavLink>
            <NavLink to="/app/orders"><ReceiptText size={20} /><span>주문</span></NavLink>
            <NavLink to="/app/me"><UserRound size={20} /><span>마이</span></NavLink>
          </nav>
        ) : null}
      </div>
    </div>
  );
}

type ConsoleKind = "store" | "ops" | "support";

/** Actor-aware route container. Store and Support chrome are delegated to their canonical workspace shells. */
export function ConsoleShell({ kind }: { kind: ConsoleKind }) {
  if (kind === "store") return <StoreConsoleShell />;
  if (kind === "support") return <SupportConsoleShell />;
  return <OperationsConsoleShell />;
}

function StoreConsoleShell() {
  const merchant = useMerchantSession();
  const enabled = merchant.status === "authenticated" || merchant.status === "initialPassword";
  const storeState = useMerchantWorkspaceStore(enabled);
  const [logoutFailed, setLogoutFailed] = useState(false);
  const selected = storeState.status === "ready" ? storeState.stores[0] ?? null : null;
  const storeName = storeState.status === "loading"
    ? "매장 확인 중"
    : storeState.status === "failed"
      ? "매장 확인 불가"
      : storeState.status === "ready" && !selected
        ? "소속 매장 없음"
        : selected?.storeName ?? "매장 선택";
  const actorName = enabled ? merchant.actor.displayName : "인증 필요";
  const roleLabel = selected
    ? selected.membershipRole === "OWNER" ? "점주" : "직원"
    : storeState.status === "loading" ? "역할 확인 중" : storeState.status === "failed" ? "역할 확인 불가" : "스토어 계정";

  async function logOut() {
    setLogoutFailed(false);
    try {
      await merchantSession.logOut();
    } catch {
      setLogoutFailed(true);
    }
  }

  return (
    <MerchantWorkspaceShell
      store={{ name: storeName }}
      actor={{ displayName: actorName, roleLabel }}
      canManageOwnerRoutes={storeState.status === "ready" && storeState.stores.some((store) => store.membershipRole === "OWNER")}
      logoutFailed={logoutFailed}
      onLogout={enabled ? () => void logOut() : undefined}
    />
  );
}

function SupportConsoleShell() {
  const operations = useOperationsAuth();
  const [logoutFailed, setLogoutFailed] = useState(false);
  const actor = operations.status === "authenticated"
    ? { displayName: "OIDC 인증됨", teamLabel: "운영팀 · 상담" }
    : operations.status === "unavailable"
      ? { displayName: "인증 설정 오류", teamLabel: "고객지원 · 확인 필요" }
      : { displayName: "로그인 필요", teamLabel: "고객지원" };

  async function logOut() {
    setLogoutFailed(false);
    try {
      await operationsAuth.logOut();
    } catch {
      setLogoutFailed(true);
    }
  }

  return (
    <SupportWorkspaceShell
      actor={actor}
      logoutFailed={logoutFailed}
      onLogout={operations.status === "authenticated" ? () => void logOut() : undefined}
    />
  );
}

function OperationsConsoleShell() {
  const operations = useOperationsAuth();
  const [logoutFailed, setLogoutFailed] = useState(false);
  const actor = operations.status === "authenticated"
    ? { displayName: "OIDC 인증됨", roleLabel: "운영자" }
    : operations.status === "unavailable"
      ? { displayName: "인증 설정 오류", roleLabel: "확인 필요" }
      : { displayName: "로그인 필요", roleLabel: "플랫폼 운영" };

  async function logOut() {
    setLogoutFailed(false);
    try {
      await operationsAuth.logOut();
    } catch {
      setLogoutFailed(true);
    }
  }

  return (
    <OperationsWorkspaceShell
      actor={actor}
      logoutFailed={logoutFailed}
      onLogout={operations.status === "authenticated" ? () => void logOut() : undefined}
    />
  );
}

type MerchantWorkspaceStoreState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; stores: MerchantStore[] }
  | { status: "failed" };

function useMerchantWorkspaceStore(enabled: boolean): MerchantWorkspaceStoreState {
  const [state, setState] = useState<MerchantWorkspaceStoreState>({ status: "idle" });
  useEffect(() => {
    if (!enabled) { setState({ status: "idle" }); return; }
    let disposed = false;
    setState({ status: "loading" });
    void requestMerchantStores()
      .then((stores) => { if (!disposed) setState({ status: "ready", stores }); })
      .catch(() => { if (!disposed) setState({ status: "failed" }); });
    return () => { disposed = true; };
  }, [enabled]);
  return state;
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
